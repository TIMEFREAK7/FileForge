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

    suspend fun pageCount(pdf: File): Int =
        calls.call(isolated, timeout(0, pdf), false, readOnly(pdf)) { e, (p) -> e.info(p) }.getInt("pages")

    /** Rendered page pixels for the image encoder; the caller closes the bundle's shared memory. */
    suspend fun renderPixels(pdf: File, page: Int, dpi: Int, widthPx: Int = 0): Bundle =
        calls.call(isolated, timeout(1, pdf), false, readOnly(pdf)) { e, (p) -> e.renderPage(p, page, dpi, widthPx) }

    /** A thumbnail or preview, rebuilt in this process from the isolated renderer's raw pixels. */
    suspend fun renderBitmap(pdf: File, page: Int, widthPx: Int): Bitmap {
        val pixels = renderPixels(pdf, page, 72, widthPx)
        try {
            return ImageConverter.fromPixels(pixels)
        } finally {
            pixels.getParcelable("mem", SharedMemory::class.java)?.close()
        }
    }

    /** Merge, split, extract, delete, reorder and rotate are all one call: the pages to keep, in order. */
    suspend fun assemble(inputs: List<File>, pages: List<PageRef>, output: File): Int {
        val spec = pages.flatMap { listOf(it.input, it.page, it.rotation) }.toIntArray()
        val fds = inputs.map { readOnly(it) } + writable(output)
        return calls.call(isolated, timeout(pages.size, *inputs.toTypedArray()), false, *fds.toTypedArray()) { e, all ->
            e.assemble(all.dropLast(1).toTypedArray(), spec, all.last())
        }.getInt("pages")
    }

    /** Returns how many embedded images were replaced by smaller JPEGs. */
    suspend fun compress(input: File, output: File, quality: Int, dpi: Int, pages: Int): Int =
        calls.call(isolated, timeout(pages, input), false, readOnly(input), writable(output)) { e, (i, o) ->
            e.compress(i, o, quality, dpi)
        }.getInt("replaced")

    /** [jpegs] must be JPEGs FileForge encoded itself. */
    suspend fun imagesToPdf(jpegs: List<File>, output: File, pageSize: PageSize): Int {
        val fds = jpegs.map { readOnly(it) } + writable(output)
        return calls.call(MediaPdfService::class.java, timeout(jpegs.size, *jpegs.toTypedArray()), true, *fds.toTypedArray()) { e, all ->
            e.imagesToPdf(all.dropLast(1).toTypedArray(), all.last(), pageSize.name)
        }.getInt("pages")
    }

    /** Self-test: (Bitmap.compress ms, YuvImage ms) for a 12 MP JPEG inside the isolated process. */
    suspend fun jpegProbe(): Pair<Long, Long> =
        calls.call(isolated, 60_000, false) { e, _ -> e.jpegProbe() }.let { it.getLong("bitmapCompressMs") to it.getLong("yuvImageMs") }

    override fun close() = calls.close()
}
