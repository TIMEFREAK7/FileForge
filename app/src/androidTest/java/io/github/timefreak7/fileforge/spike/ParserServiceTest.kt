package io.github.timefreak7.fileforge.spike

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Phase 0: isolated-process round-trip and the MediaCodec question. Run on the OnePlus 13. */
@RunWith(AndroidJUnit4::class)
class ParserServiceTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private lateinit var service: IParserService

    @Before fun bind() {
        val binder = serviceRule.bindService(Intent(Phase0.context, ParserService::class.java))
        service = IParserService.Stub.asInterface(binder)
    }

    @Test fun serviceIsIsolated() {
        assertTrue(service.isIsolated)
    }

    @Test fun isolatedProcessCannotReadAppFiles() {
        val secret = File(Phase0.context.filesDir, "secret.txt").apply { writeText("family data") }
        val result = service.readProbe(secret.absolutePath)
        Phase0.report("isolated read of app file", result)
        assertTrue(result, result.startsWith("denied"))
    }

    @Test fun pdfPageRoundTripsThroughSharedMemory() {
        val pfd = ParcelFileDescriptor.open(Phase0.makeTestPdf(), ParcelFileDescriptor.MODE_READ_ONLY)
        val start = System.nanoTime()
        val out = service.renderPage(pfd, 0, 1080)
        val ms = (System.nanoTime() - start) / 1_000_000
        out.classLoader = SharedMemory::class.java.classLoader
        val w = out.getInt("w")
        val h = out.getInt("h")
        val mem = out.getParcelable("mem", SharedMemory::class.java)!!
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val buffer = mem.mapReadOnly()
        bitmap.copyPixelsFromBuffer(buffer)
        SharedMemory.unmap(buffer)
        mem.close()

        assertEquals(1080, w)
        assertEquals(1080 * 842 / 595, h)
        // Top half of page 1 is black, bottom half mostly white.
        assertEquals(Color.BLACK, bitmap.getPixel(w / 2, h / 4))
        assertEquals(Color.WHITE, bitmap.getPixel(w / 2, h - 10))
        Phase0.report("isolated PdfRenderer page via SharedMemory", "ok: ${w}x$h in $ms ms")
    }

    @Test fun hardwareEncodeMainVersusIsolated() {
        val main = EncodeProbe.run()
        val isolated = service.encodeProbe()
        Phase0.report("hardware H.264 encode, main process", main)
        Phase0.report("hardware H.264 encode, isolated process", isolated)
        // Only the control must pass: the isolated result is the finding that decides the process split.
        assertTrue(main, main.startsWith("ok"))
    }
}
