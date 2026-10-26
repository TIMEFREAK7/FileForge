package io.github.timefreak7.fileforge.spike

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 0: does PDFBox shape Devanagari? Writes two PNGs to Downloads/FileForge-phase0:
 * devanagari-pdfbox.png (PDFBox output) and devanagari-android.png (Android's own layout,
 * which shapes correctly). Compare them by eye: broken conjuncts or a misplaced "ि" in the
 * PDFBox image confirm the spec's stamp-as-image plan.
 */
@RunWith(AndroidJUnit4::class)
class DevanagariPdfBoxTest {
    private val phrase = "नमस्ते क्षत्रिय हिन्दी श्री"

    @Test fun pdfBoxDevanagari() {
        PDFBoxResourceLoader.init(Phase0.context)
        val pdf = File(Phase0.context.cacheDir, "devanagari-pdfbox.pdf")
        val fontUsed = PDDocument().use { doc ->
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
        Phase0.report("PDFBox Devanagari font", fontUsed)

        val rendered = PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            r.openPage(0).use { p ->
                Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        savePng(Bitmap.createBitmap(rendered, 0, 0, 1240, 400), "devanagari-pdfbox.png")
        savePng(androidLayout(), "devanagari-android.png")
        Phase0.saveToDownloads("devanagari-pdfbox.pdf", "application/pdf") { out -> pdf.inputStream().use { it.copyTo(out) } }
        Phase0.report("PDFBox Devanagari", "rendered; compare the two PNGs in Downloads/FileForge-phase0")
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
        Phase0.report("PDFBox Devanagari font", "none loaded; tried $errors")
        throw AssertionError("No system Devanagari font PDFBox can load: $errors")
    }

    private fun androidLayout(): Bitmap {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { textSize = 28f * 1240 / 595 }
        val layout = StaticLayout.Builder.obtain(phrase, 0, phrase.length, paint, 1160)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        return Bitmap.createBitmap(1240, 400, Bitmap.Config.ARGB_8888).also {
            val canvas = android.graphics.Canvas(it)
            canvas.drawColor(Color.WHITE)
            canvas.translate(40f * 1240 / 595, 80f)
            layout.draw(canvas)
        }
    }

    private fun savePng(bitmap: Bitmap, name: String) {
        Phase0.saveToDownloads(name, "image/png") { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
