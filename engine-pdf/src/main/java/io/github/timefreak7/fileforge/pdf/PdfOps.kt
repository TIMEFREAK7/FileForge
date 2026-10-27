package io.github.timefreak7.fileforge.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import io.github.timefreak7.fileforge.core.Limits
import io.github.timefreak7.fileforge.core.PageSize
import io.github.timefreak7.fileforge.image.ConversionException
import io.github.timefreak7.fileforge.image.ImageConverter
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** The PDF operations. Everything except [imagesToPdf] runs in the isolated process on untrusted PDFs. */
object PdfOps {

    /** Opens a PDF read straight from its descriptor; no temp files, no full copy in memory. */
    private fun open(pdf: ParcelFileDescriptor): PDDocument {
        val doc = try {
            val parser = PDFParser(FdRandomAccess(FileInputStream(pdf.fileDescriptor).channel), "", ScratchFile.getMainMemoryOnlyInstance())
            parser.parse()
            parser.pdDocument
        } catch (e: InvalidPasswordException) {
            // shortcut: open passwords are not asked for yet; spec says ask once per job. Planned next.
            throw ConversionException("This PDF needs a password to open. Password support comes in a later update.")
        } catch (e: OutOfMemoryError) {
            throw ConversionException("This PDF is too large to open on this phone.")
        } catch (e: Exception) {
            throw ConversionException("This file is damaged or is not a PDF FileForge can read.")
        }
        if (doc.numberOfPages > Limits.MAX_PDF_PAGES) {
            doc.close()
            throw ConversionException("This PDF has ${doc.numberOfPages} pages; the limit is ${Limits.MAX_PDF_PAGES}.")
        }
        if (doc.numberOfPages == 0) {
            doc.close()
            throw ConversionException("This PDF has no pages.")
        }
        return doc
    }

    private fun out(pdf: ParcelFileDescriptor) = BufferedOutputStream(FileOutputStream(pdf.fileDescriptor), 64 * 1024)

    private fun save(doc: PDDocument, output: ParcelFileDescriptor) {
        try {
            out(output).let {
                doc.save(it)
                it.flush()
            }
        } catch (e: OutOfMemoryError) {
            throw ConversionException("This PDF is too large to save on this phone.")
        }
    }

    fun info(pdf: ParcelFileDescriptor): Int = open(pdf).use { it.numberOfPages }

    /** Renders with Android's own PDF renderer; pixels go back through shared memory. */
    fun renderPage(pdf: ParcelFileDescriptor, index: Int, dpi: Int, widthPx: Int): Bundle {
        val renderer = try {
            PdfRenderer(pdf)
        } catch (e: SecurityException) {
            throw ConversionException("This PDF needs a password to open. Password support comes in a later update.")
        } catch (e: Exception) {
            throw ConversionException("This file is damaged or is not a PDF FileForge can read.")
        }
        renderer.use { r ->
            if (index !in 0 until r.pageCount) throw ConversionException("This PDF has no page ${index + 1}.")
            r.openPage(index).use { page ->
                var w = if (widthPx > 0) widthPx else (page.width * dpi / 72f).roundToInt()
                var h = (w.toLong() * page.height / page.width).toInt()
                val pixels = w.toLong() * h
                if (pixels > Limits.MAX_RENDER_PIXELS) {
                    val scale = kotlin.math.sqrt(Limits.MAX_RENDER_PIXELS.toDouble() / pixels)
                    w = (w * scale).toInt()
                    h = (h * scale).toInt()
                }
                val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return ImageConverter.toPixels(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    /** One PDF from chosen pages of the inputs: (input, page, extra rotation) triples. */
    fun assemble(inputs: Array<ParcelFileDescriptor>, spec: IntArray, output: ParcelFileDescriptor): Int {
        if (spec.isEmpty() || spec.size % 3 != 0) throw ConversionException("No pages were chosen.")
        val docs = ArrayList<PDDocument>()
        try {
            inputs.forEach { docs += open(it) }
            PDDocument(MemoryUsageSetting.setupMainMemoryOnly()).use { result ->
                for (i in spec.indices step 3) {
                    val doc = docs.getOrNull(spec[i]) ?: throw ConversionException("Unknown input file.")
                    val pageIndex = spec[i + 1]
                    if (pageIndex !in 0 until doc.numberOfPages) throw ConversionException("A chosen page does not exist.")
                    val page = result.importPage(doc.getPage(pageIndex))
                    page.rotation = Math.floorMod(page.rotation + spec[i + 2], 360)
                }
                save(result, output)
                return result.numberOfPages
            }
        } finally {
            docs.forEach { runCatching { it.close() } }
        }
    }

    /**
     * Re-encodes large embedded images as JPEG, no wider than [dpi] across the page. An image is replaced
     * only when the JPEG is smaller. Images with transparency, 1-bit scans and stencils stay as they are.
     */
    fun compress(input: ParcelFileDescriptor, output: ParcelFileDescriptor, quality: Int, dpi: Int): Int {
        open(input).use { doc ->
            val done = IdentityHashMap<COSBase, PDImageXObject?>()
            var replaced = 0
            for (page in doc.pages) {
                val resources = page.resources ?: continue
                val maxW = (page.mediaBox.width / 72f * dpi).roundToInt()
                val maxH = (page.mediaBox.height / 72f * dpi).roundToInt()
                for (name in resources.xObjectNames.toList()) {
                    if (!resources.isImageXObject(name)) continue
                    val image = resources.getXObject(name) as? PDImageXObject ?: continue
                    val smaller = done.getOrPut(image.cosObject) { recompress(doc, image, quality, maxW, maxH) }
                    if (smaller != null) {
                        resources.put(name, smaller)
                        replaced++
                    }
                }
            }
            save(doc, output)
            return replaced
        }
    }

    private fun recompress(doc: PDDocument, image: PDImageXObject, quality: Int, maxW: Int, maxH: Int): PDImageXObject? {
        if (image.isStencil || image.bitsPerComponent == 1 || image.width * image.height < 40_000) return null
        val originalBytes = image.cosObject.length
        val decoded = try {
            if (image.softMask != null) return null
            image.image
        } catch (e: Exception) {
            return null // a filter PDFBox cannot decode (e.g. JBIG2): keep the original
        } catch (e: OutOfMemoryError) {
            return null
        } ?: return null
        try {
            val scale = minOf(1f, maxW.toFloat() / decoded.width, maxH.toFloat() / decoded.height)
            val w = (decoded.width * scale).roundToInt().coerceAtLeast(2)
            val h = (decoded.height * scale).roundToInt().coerceAtLeast(2)
            val jpeg = Jpeg.encode(decoded, w, h, quality)
            if (jpeg.size >= originalBytes) return null
            return JPEGFactory.createFromByteArray(doc, jpeg)
        } finally {
            decoded.recycle()
        }
    }

    /** Runs in ":media": the JPEGs were just encoded by FileForge, so nothing untrusted is parsed here. */
    fun imagesToPdf(jpegs: Array<ParcelFileDescriptor>, output: ParcelFileDescriptor, pageSize: PageSize, tempDir: File): Int {
        val memory = MemoryUsageSetting.setupMixed(64L * 1024 * 1024).setTempDir(tempDir)
        PDDocument(memory).use { doc ->
            for (fd in jpegs) {
                val image = FileInputStream(fd.fileDescriptor).use { JPEGFactory.createFromStream(doc, it) }
                val landscape = image.width > image.height
                val box = when (pageSize) {
                    // Longest side 842 pt (A4 height), the image's own shape.
                    PageSize.FIT -> {
                        val s = 842f / maxOf(image.width, image.height)
                        PDRectangle(image.width * s, image.height * s)
                    }
                    else -> if (landscape) PDRectangle(pageSize.heightPt, pageSize.widthPt) else PDRectangle(pageSize.widthPt, pageSize.heightPt)
                }
                val page = PDPage(box)
                doc.addPage(page)
                val margin = if (pageSize == PageSize.FIT) 0f else 18f
                val s = minOf((box.width - 2 * margin) / image.width, (box.height - 2 * margin) / image.height)
                val w = image.width * s
                val h = image.height * s
                PDPageContentStream(doc, page).use { it.drawImage(image, (box.width - w) / 2, (box.height - h) / 2, w, h) }
            }
            save(doc, output)
            return doc.numberOfPages
        }
    }

    /** Self-test: is Bitmap.compress's JPEG encoder slow in this process, and is the YuvImage route not? */
    fun jpegProbe(): Bundle {
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 110)) }
        try {
            val t0 = SystemClock.elapsedRealtime()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, ByteArrayOutputStream())
            val t1 = SystemClock.elapsedRealtime()
            Jpeg.encode(bitmap, bitmap.width, bitmap.height, 80)
            val t2 = SystemClock.elapsedRealtime()
            return Bundle().apply {
                putLong("bitmapCompressMs", t1 - t0)
                putLong("yuvImageMs", t2 - t1)
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * JPEG through YuvImage (the framework's libjpeg path) instead of Bitmap.compress, whose encoder stalls
 * about 5 s per image in an isolated process on the OnePlus 13 (Phase 1 speed check).
 */
object Jpeg {
    fun encode(source: Bitmap, width: Int, height: Int, quality: Int): ByteArray {
        // 4:2:0 chroma needs even sizes.
        val w = width and 1.inv()
        val h = height and 1.inv()
        val scaled = if (source.width == w && source.height == h) source else Bitmap.createScaledBitmap(source, w, h, true)
        try {
            val argb = IntArray(w * h)
            scaled.getPixels(argb, 0, w, 0, 0, w, h)
            val nv21 = ByteArray(w * h * 3 / 2)
            var uv = w * h
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val c = argb[y * w + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    // Full-range BT.601, as JFIF expects.
                    nv21[y * w + x] = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255).toByte()
                    if (y % 2 == 0 && x % 2 == 0) {
                        nv21[uv++] = (128 + 0.5f * r - 0.418688f * g - 0.081312f * b).toInt().coerceIn(0, 255).toByte()
                        nv21[uv++] = (128 - 0.168736f * r - 0.331264f * g + 0.5f * b).toInt().coerceIn(0, 255).toByte()
                    }
                }
            }
            val out = ByteArrayOutputStream()
            if (!YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, out)) {
                throw ConversionException("An image in this PDF could not be re-encoded.")
            }
            return out.toByteArray()
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
