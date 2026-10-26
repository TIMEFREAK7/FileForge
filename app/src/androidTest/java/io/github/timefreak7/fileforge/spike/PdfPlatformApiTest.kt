package io.github.timefreak7.fileforge.spike

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 0: which PDFBox jobs can Android 16's own PdfRenderer take over?
 * Text search would verify redaction (S13); form widgets and write() could fill AcroForms.
 */
@RunWith(AndroidJUnit4::class)
class PdfPlatformApiTest {

    @Test fun textSearchAndFormApis() {
        val pfd = ParcelFileDescriptor.open(Phase0.makeTestPdf(), ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            renderer.openPage(0).use { page ->
                val text = page.textContents.joinToString(" ") { it.text }
                val matches = page.searchText("probe")
                val widgets = page.formWidgetInfos
                Phase0.report("platform PDF text extraction", "\"${text.trim()}\"")
                Phase0.report("platform PDF search", "${matches.size} match(es) for \"probe\"")
                Phase0.report("platform PDF form type", "${renderer.pdfFormType}, ${widgets.size} widget(s) on page 1")
                assertTrue("search found nothing", matches.isNotEmpty())
            }
        }
    }
}
