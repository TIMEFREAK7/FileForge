package io.github.timefreak7.fileforge.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SharedMemory
import io.github.timefreak7.fileforge.core.Limits
import io.github.timefreak7.fileforge.core.PageSize
import io.github.timefreak7.fileforge.image.ImageConverter
import io.github.timefreak7.fileforge.image.ImageEngineClient.Companion.readOnly
import io.github.timefreak7.fileforge.image.ImageEngineClient.Companion.writable
import io.github.timefreak7.fileforge.image.ParserCalls
import io.github.timefreak7.fileforge.image.ParserFailure
import java.io.Closeable
import java.io.File

/** One page of one input, with extra rotation in degrees: the unit the organiser and split work in. */
data class PageRef(val input: Int, val page: Int, val rotation: Int = 0)

/**
 * Main-process side of the PDF engine. Every call throws [ParserFailure] with a plain-language message.
 * PDFs are parsed only in the isolated process; ":media" only assembles JPEGs FileForge encoded.
 */
class PdfEngineClient(context: Context) : Closeable {
    private val calls = ParserCalls(context, IPdfEngine.Stub::asInterface) { it.pid() }
    private val isolated = IsolatedPdfService::class.java

    private fun timeout(pages: Int, vararg files: File) = Limits.pdfTimeoutMs(pages, files.sumOf { it.length() })

    /** Passwords below are null when none; a protected PDF without one fails with [PASSWORD_NEEDED]. */
    suspend fun pageCount(pdf: File, password: String? = null): Int =
        calls.call(isolated, timeout(0, pdf), false, readOnly(pdf)) { e, (p) -> e.info(p, password.orEmpty()) }.getInt("pages")

    /** Rendered page pixels for the image encoder; the caller closes the bundle's shared memory. */
    suspend fun renderPixels(pdf: File, page: Int, dpi: Int, widthPx: Int = 0, password: String? = null): Bundle =
        calls.call(isolated, timeout(1, pdf), false, readOnly(pdf)) { e, (p) -> e.renderPage(p, password.orEmpty(), page, dpi, widthPx) }

    /** A thumbnail or preview, rebuilt in this process from the isolated renderer's raw pixels. */
    suspend fun renderBitmap(pdf: File, page: Int, widthPx: Int, password: String? = null): Bitmap {
        val pixels = renderPixels(pdf, page, 72, widthPx, password)
        try {
            return ImageConverter.fromPixels(pixels)
        } finally {
            pixels.getParcelable("mem", SharedMemory::class.java)?.close()
        }
    }

    /**
     * Merge, split, extract, delete, reorder and rotate are all one call: the pages to keep, in order.
     * The output keeps the first protected input's password unless [removePassword]; [newPassword] sets one.
     */
    suspend fun assemble(
        inputs: List<File>,
        pages: List<PageRef>,
        output: File,
        passwords: List<String?> = emptyList(),
        removePassword: Boolean = false,
        newPassword: String? = null,
    ): Int {
        val spec = pages.flatMap { listOf(it.input, it.page, it.rotation) }.toIntArray()
        val pw = Array(inputs.size) { passwords.getOrNull(it).orEmpty() }
        val fds = inputs.map { readOnly(it) } + writable(output)
        return calls.call(isolated, timeout(pages.size, *inputs.toTypedArray()), false, *fds.toTypedArray()) { e, all ->
            e.assemble(all.dropLast(1).toTypedArray(), pw, spec, all.last(), removePassword, newPassword.orEmpty())
        }.getInt("pages")
    }

    /** Returns how many embedded images were replaced by smaller JPEGs. A protected input stays protected unless [removePassword]. */
    suspend fun compress(input: File, output: File, quality: Int, dpi: Int, pages: Int, password: String? = null, removePassword: Boolean = false): Int =
        calls.call(isolated, timeout(pages, input), false, readOnly(input), writable(output)) { e, (i, o) ->
            e.compress(i, password.orEmpty(), o, quality, dpi, removePassword)
        }.getInt("replaced")

    /** [jpegs] must be JPEGs FileForge encoded itself. */
    suspend fun imagesToPdf(jpegs: List<File>, output: File, pageSize: PageSize): Int {
        val fds = jpegs.map { readOnly(it) } + writable(output)
        return calls.call(MediaPdfService::class.java, timeout(jpegs.size, *jpegs.toTypedArray()), true, *fds.toTypedArray()) { e, all ->
            e.imagesToPdf(all.dropLast(1).toTypedArray(), all.last(), pageSize.name)
        }.getInt("pages")
    }

    /** Each page's size as shown, in points: width, height pairs. */
    suspend fun pageSizes(pdf: File, password: String? = null): FloatArray =
        calls.call(isolated, timeout(0, pdf), false, readOnly(pdf)) { e, (p) -> e.pageSizes(p, password.orEmpty()) }.getFloatArray("sizes")!!

    /** The fillable fields; throws [ParserFailure] with the XFA message when the form cannot be filled. */
    suspend fun formFields(pdf: File, password: String? = null): Pair<List<FormField>, Boolean> {
        val b = calls.call(isolated, timeout(0, pdf), false, readOnly(pdf)) { e, (p) -> e.formFields(p, password.orEmpty()) }
        return FormField.listFromJson(b.getString("fields") ?: "[]") to b.getBoolean("xfa")
    }

    /** Applies [spec] (written as a file next to [output]) with its PNG [stamps]. */
    suspend fun edit(input: File, spec: File, stamps: List<File>, output: File, password: String? = null, removePassword: Boolean = false): Int {
        val fds = listOf(readOnly(input), readOnly(spec)) + stamps.map { readOnly(it) } + writable(output)
        return calls.call(isolated, timeout(0, input, spec, *stamps.toTypedArray()), false, *fds.toTypedArray()) { e, all ->
            e.edit(all[0], password.orEmpty(), all[1], all.drop(2).dropLast(1).toTypedArray(), all.last(), removePassword)
        }.getInt("pages")
    }

    /** Redacts the REDACT areas in [spec] (S13). */
    suspend fun redact(input: File, spec: File, output: File, password: String? = null, removePassword: Boolean = false): Int =
        calls.call(isolated, timeout(0, input, spec) * 2, false, readOnly(input), readOnly(spec), writable(output)) { e, (i, s, o) ->
            e.redact(i, password.orEmpty(), s, o, removePassword)
        }.getInt("pages")

    /** Debug self-test only. */
    suspend fun makeTestPdf(kind: String, phrase: String, output: File) {
        calls.call(isolated, 60_000, false, writable(output)) { e, (o) -> e.makeTestPdf(kind, phrase, o) }
    }

    /** Self-test: (Bitmap.compress ms, YuvImage ms) for a 12 MP JPEG inside the isolated process. */
    suspend fun jpegProbe(): Pair<Long, Long> =
        calls.call(isolated, 60_000, false) { e, _ -> e.jpegProbe() }.let { it.getLong("bitmapCompressMs") to it.getLong("yuvImageMs") }

    override fun close() = calls.close()

    companion object {
        /** [ParserFailure.code]: the PDF is protected and no password was given. */
        const val PASSWORD_NEEDED = "password"
        /** [ParserFailure.code]: the password given does not open the PDF. */
        const val WRONG_PASSWORD = "wrongPassword"
        /** The [PASSWORD_NEEDED] message; a prompt shows any other password message (a wrong one) as an error. */
        const val PASSWORD_NEEDED_MESSAGE = "This PDF is protected. Enter its password to continue."

        val ParserFailure.isPassword: Boolean get() = code == PASSWORD_NEEDED || code == WRONG_PASSWORD
    }
}
