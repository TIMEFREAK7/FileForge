package io.github.timefreak7.fileforge.core

/** S5: hard limits on input. A file over a limit fails with a plain error instead of exhausting the phone. */
object Limits {
    const val MAX_IMAGE_BYTES = 200L * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 100_000_000L
    const val MAX_FILES_PER_JOB = 200

    /** Per-image timeout: 30 s plus 2 s per MB, capped at 5 minutes. */
    fun imageTimeoutMs(bytes: Long): Long = (30_000L + bytes / (1024 * 1024) * 2_000L).coerceAtMost(300_000L)
}
