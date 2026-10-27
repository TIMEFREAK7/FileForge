package io.github.timefreak7.fileforge.pdf

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.github.timefreak7.fileforge.core.PageSize
import io.github.timefreak7.fileforge.image.ConversionException

/** The PDF engine's binder. Declared twice in the manifest so it runs in two kinds of process. */
abstract class PdfEngineService : Service() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }

    private val binder = object : IPdfEngine.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun info(pdf: ParcelFileDescriptor) = safely(pdf) { putInt("pages", PdfOps.info(pdf)) }

        override fun renderPage(pdf: ParcelFileDescriptor, page: Int, dpi: Int, widthPx: Int) =
            safely(pdf) { putAll(PdfOps.renderPage(pdf, page, dpi, widthPx)) }

        override fun assemble(inputs: Array<ParcelFileDescriptor>, spec: IntArray, output: ParcelFileDescriptor) =
            safely(*inputs, output) { putInt("pages", PdfOps.assemble(inputs, spec, output)) }

        override fun compress(input: ParcelFileDescriptor, output: ParcelFileDescriptor, quality: Int, dpi: Int) =
            safely(input, output) { putInt("replaced", PdfOps.compress(input, output, quality.coerceIn(1, 100), dpi.coerceIn(36, 600))) }

        override fun imagesToPdf(jpegs: Array<ParcelFileDescriptor>, output: ParcelFileDescriptor, pageSize: String) =
            safely(*jpegs, output) {
                putInt("pages", PdfOps.imagesToPdf(jpegs, output, PageSize.valueOf(pageSize), cacheDir))
            }

        override fun jpegProbe() = safely { putAll(PdfOps.jpegProbe()) }
    }

    /** Runs one call, turns any failure into a plain-language "error", and always closes the descriptors. */
    private fun safely(vararg fds: ParcelFileDescriptor, block: Bundle.() -> Unit): Bundle = try {
        Bundle().apply {
            block()
            putBoolean("ok", true)
        }
    } catch (e: ConversionException) {
        failure(e.message ?: "The PDF could not be processed.")
    } catch (e: OutOfMemoryError) {
        failure("This PDF is too large for this phone.")
    } catch (t: Throwable) {
        failure("The PDF could not be processed (${t.javaClass.simpleName}).")
    } finally {
        fds.forEach { runCatching { it.close() } }
    }

    private fun failure(message: String) = Bundle().apply {
        putBoolean("ok", false)
        putString("error", message)
    }

    override fun onBind(intent: Intent): IBinder = binder
}

/** S4: android:isolatedProcess, no storage or network rights. Parses every PDF. */
class IsolatedPdfService : PdfEngineService()

/** ":media": builds PDFs from JPEGs FileForge just encoded, with temp files for large documents. */
class MediaPdfService : PdfEngineService()
