package io.github.timefreak7.fileforge.spike

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import io.github.timefreak7.fileforge.media.Mp3Encoder
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Phase 0 spike checks, run from the in-app button and from the instrumented tests.
 * Throwaway: Phase 1 replaces the spike package.
 */
class Phase0Checks(private val context: Context) {

    /** [ok] is null for findings that decide the plan rather than pass or fail. */
    data class Result(val name: String, val ok: Boolean?, val detail: String)

    class Devanagari(val result: Result, val pdfBox: Bitmap?, val android: Bitmap)

    fun isolated(service: IParserService) = check("Parser service is isolated") {
        val isolated = service.isIsolated
        Result(it, isolated, if (isolated) "yes" else "no: the service runs with normal app rights")
    }

    fun isolatedCannotReadAppFiles(service: IParserService) = check("Isolated process cannot read app files") {
        val secret = File(context.filesDir, "phase0-secret.txt").apply { writeText("family data") }
        val result = service.readProbe(secret.absolutePath)
        Result(it, result.startsWith("denied"), result)
    }

    fun isolatedPdfRender(service: IParserService) = check("PDF page rendered in isolated process") {
        val pfd = ParcelFileDescriptor.open(makeTestPdf(), ParcelFileDescriptor.MODE_READ_ONLY)
        val start = System.nanoTime()
        val out = service.renderPage(pfd, 0, 1080)
        val ms = (System.nanoTime() - start) / 1_000_000
        out.classLoader = SharedMemory::class.java.classLoader
        val w = out.getInt("w")
        val h = out.getInt("h")
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.getParcelable("mem", SharedMemory::class.java)!!.use { mem ->
            val buffer = mem.mapReadOnly()
            bitmap.copyPixelsFromBuffer(buffer)
            SharedMemory.unmap(buffer)
        }
        // Page 1 of the test PDF has its top half black and its bottom edge white.
        val ok = w == 1080 && h == 1080 * 842 / 595 &&
            bitmap.getPixel(w / 2, h / 4) == Color.BLACK && bitmap.getPixel(w / 2, h - 10) == Color.WHITE
        Result(it, ok, "${w}x$h in $ms ms" + if (ok) "" else ", pixels wrong")
    }

    fun encodeMainProcess() = check("Hardware H.264 encode, main process") {
        val r = EncodeProbe.run()
        Result(it, r.startsWith("ok"), r)
    }

    fun encodeIsolatedProcess(service: IParserService) = check("Hardware H.264 encode, isolated process") {
        Result(it, null, service.encodeProbe())
    }

    fun mp3RoundTrip() = check("MP3 (LAME) encode and read back") {
        val rate = 44_100
        val pcm = ShortArray(rate * 2) { i -> (sin(2 * PI * 440 * (i / 2) / rate) * 12_000).toInt().toShort() }
        val file = File(context.cacheDir, "phase0-sine.mp3").apply { writeBytes(Mp3Encoder.encode(pcm, rate, 2)) }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME)
            val ms = format.getLong(MediaFormat.KEY_DURATION) / 1000
            Result(it, mime == MediaFormat.MIMETYPE_AUDIO_MPEG && ms in 950..1150, "$mime, $ms ms (expect about 1000)")
        } finally {
            extractor.release()
        }
    }

    fun platformPdfApis() = check("Android 16 PDF search and forms") {
        val pfd = ParcelFileDescriptor.open(makeTestPdf(), ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            renderer.openPage(0).use { page ->
                val text = page.textContents.joinToString(" ") { c -> c.text }.trim()
                val matches = page.searchText("probe").size
                Result(
                    it, matches > 0,
                    "text \"$text\", $matches match(es) for \"probe\", form type ${renderer.pdfFormType}, " +
                        "${page.formWidgetInfos.size} widget(s)",
                )
            }
        }
    }

    /** Draws the same phrase with PDFBox and with Android's text engine, for comparison by eye. */
    fun devanagari(): Devanagari {
        val phrase = "नमस्ते क्षत्रिय हिन्दी श्री"
        val android = androidLayout(phrase)
        return try {
            PDFBoxResourceLoader.init(context)
            val pdf = File(context.cacheDir, "phase0-devanagari.pdf")
            val fontName = PDDocument().use { doc ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val (font, name) = loadSystemDevanagariFont(doc)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, 28f)
                    cs.newLineAtOffset(40f, 700f)
                    cs.showText(phrase)
                    cs.endText()
                }
                doc.save(pdf)
                name
            }
            val page = PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                r.openPage(0).use { p ->
                    Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
            Devanagari(
                Result("PDFBox Devanagari", null, "drawn with $fontName; compare the two images below"),
                Bitmap.createBitmap(page, 0, 0, 1240, 400),
                android,
            )
        } catch (t: Throwable) {
            Devanagari(Result("PDFBox Devanagari", false, "${t.javaClass.simpleName}: ${t.message}"), null, android)
        }
    }

    /** Tries each system Devanagari font until PDFBox accepts one (it only takes TrueType outlines). */
    private fun loadSystemDevanagariFont(doc: PDDocument): Pair<PDType0Font, String> {
        val candidates = File("/system/fonts").listFiles { f -> "Devanagari" in f.name }.orEmpty()
            .sortedBy { if ("UI" in it.name) 1 else 0 }
        val errors = mutableListOf<String>()
        for (f in candidates) {
            try {
                return PDType0Font.load(doc, f) to f.name
            } catch (e: Exception) {
                errors += "${f.name}: ${e.javaClass.simpleName}"
            }
        }
        throw IllegalStateException("no system Devanagari font PDFBox can load; tried $errors")
    }

    private fun androidLayout(phrase: String): Bitmap {
        val scale = 1240f / 595
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 28f * scale }
        val layout = StaticLayout.Builder.obtain(phrase, 0, phrase.length, paint, 1160)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        return Bitmap.createBitmap(1240, 400, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(Color.WHITE)
            canvas.translate(40f * scale, 80f)
            layout.draw(canvas)
        }
    }

    /** Two-page A4 test PDF: page 1 has the top half filled black and the phrase "FileForge probe". */
    fun makeTestPdf(): File {
        val file = File(context.cacheDir, "phase0-test.pdf")
        val doc = PdfDocument()
        repeat(2) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            if (i == 0) {
                page.canvas.drawRect(0f, 0f, 595f, 421f, Paint().apply { color = Color.BLACK })
                page.canvas.drawText("FileForge probe", 40f, 600f, Paint().apply { textSize = 24f })
            }
            doc.finishPage(page)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private inline fun check(name: String, block: (String) -> Result): Result = try {
        block(name)
    } catch (t: Throwable) {
        Result(name, false, "${t.javaClass.simpleName}: ${t.message}")
    }
}
