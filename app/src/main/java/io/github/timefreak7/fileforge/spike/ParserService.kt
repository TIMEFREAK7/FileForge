package io.github.timefreak7.fileforge.spike

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.system.OsConstants
import java.io.File

/** Phase 0 spike: runs with android:isolatedProcess, so it only sees the descriptors it is handed. */
class ParserService : Service() {

    private val binder = object : IParserService.Stub() {
        override fun isIsolated(): Boolean = Process.isIsolated()

        override fun renderPage(pdf: ParcelFileDescriptor, pageIndex: Int, widthPx: Int): Bundle =
            PdfRenderer(pdf).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val heightPx = widthPx * page.height / page.width
                    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    // A binder transaction is capped near 1 MB, so pixels travel in shared memory.
                    val mem = SharedMemory.create("page", bitmap.byteCount)
                    val buffer = mem.mapReadWrite()
                    bitmap.copyPixelsToBuffer(buffer)
                    SharedMemory.unmap(buffer)
                    mem.setProtect(OsConstants.PROT_READ)
                    bitmap.recycle()
                    Bundle().apply {
                        putParcelable("mem", mem)
                        putInt("w", widthPx)
                        putInt("h", heightPx)
                    }
                }
            }

        override fun encodeProbe(): String = EncodeProbe.run()

        override fun readProbe(path: String): String = try {
            "read ${File(path).readBytes().size} bytes"
        } catch (e: Exception) {
            "denied: ${e.javaClass.simpleName}"
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
}
