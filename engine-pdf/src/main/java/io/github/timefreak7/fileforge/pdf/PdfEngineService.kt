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

        override fun info(pdf: ParcelFileDescriptor, password: String?) = safely(pdf) { putInt("pages", PdfOps.info(pdf, password.orEmpty())) }

        override fun renderPage(pdf: ParcelFileDescriptor, password: String?, page: Int, dpi: Int, widthPx: Int) =
            safely(pdf) { putAll(PdfOps.renderPage(pdf, password.orEmpty(), page, dpi, widthPx)) }

        override fun assemble(
            inputs: Array<ParcelFileDescriptor>,
            passwords: Array<String?>,
            spec: IntArray,
            output: ParcelFileDescriptor,
            removePassword: Boolean,
            newPassword: String?,
        ) = safely(*inputs, output) {
            val pw = List(inputs.size) { passwords.getOrNull(it).orEmpty() }
            putInt("pages", PdfOps.assemble(inputs, pw, spec, output, removePassword, newPassword.orEmpty()))
        }

        override fun compress(
            input: ParcelFileDescriptor,
            password: String?,
            output: ParcelFileDescriptor,
            quality: Int,
            dpi: Int,
            removePassword: Boolean,
        ) = safely(input, output) {
            putInt("replaced", PdfOps.compress(input, password.orEmpty(), output, quality.coerceIn(1, 100), dpi.coerceIn(36, 600), removePassword))
        }

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
        failure(e.message ?: "The PDF could not be processed.", e.code)
    } catch (e: OutOfMemoryError) {
        failure("This PDF is too large for this phone.")
    } catch (t: Throwable) {
        failure("The PDF could not be processed (${t.javaClass.simpleName}).")
    } finally {
        fds.forEach { runCatching { it.close() } }
    }

    private fun failure(message: String, code: String? = null) = Bundle().apply {
        putBoolean("ok", false)
        putString("error", message)
        putString("code", code)
    }

    override fun onBind(intent: Intent): IBinder = binder
}

/** S4: android:isolatedProcess, no storage or network rights. Parses every PDF. */
class IsolatedPdfService : PdfEngineService()

/** ":media": builds PDFs from JPEGs FileForge just encoded, with temp files for large documents. */
class MediaPdfService : PdfEngineService()
