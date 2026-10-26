package io.github.timefreak7.fileforge.spike

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.provider.MediaStore
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.OutputStream

/** Shared helpers for the Phase 0 spikes. Read results with: adb logcat -d -s Phase0 */
object Phase0 {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun report(name: String, result: String) {
        Log.i("Phase0", "$name: $result")
    }

    /** Saves to Downloads/FileForge-phase0 so the file survives the test uninstall and opens in the Files app. */
    fun saveToDownloads(name: String, mime: String, write: (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/FileForge-phase0")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use(write)
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
}
