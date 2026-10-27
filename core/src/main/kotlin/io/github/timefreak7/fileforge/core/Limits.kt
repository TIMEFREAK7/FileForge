package io.github.timefreak7.fileforge.core

/** S5: hard limits on input. A file over a limit fails with a plain error instead of exhausting the phone. */
object Limits {
    const val MAX_IMAGE_BYTES = 200L * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 100_000_000L
    const val MAX_FILES_PER_JOB = 200
    const val MAX_PDF_BYTES = 500L * 1024 * 1024
    const val MAX_PDF_PAGES = 2_000
    /** Largest page FileForge renders to an image (about 300 DPI on A3). */
    const val MAX_RENDER_PIXELS = 50_000_000L

    fun maxBytes(format: Format) = if (format == Format.PDF) MAX_PDF_BYTES else MAX_IMAGE_BYTES

    /** Per-image timeout: 30 s plus 2 s per MB, capped at 5 minutes. */
    fun imageTimeoutMs(bytes: Long): Long = (30_000L + bytes / (1024 * 1024) * 2_000L).coerceAtMost(300_000L)

    /** Per PDF operation: 60 s plus 2 s per page and 1 s per MB, capped at 30 minutes. */
    fun pdfTimeoutMs(pages: Int, bytes: Long): Long =
        (60_000L + pages * 2_000L + bytes / (1024 * 1024) * 1_000L).coerceAtMost(1_800_000L)
}
