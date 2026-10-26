package io.github.timefreak7.fileforge.core

/** S7: incoming names are display text only; output names are sanitised before reaching the output folder. */
object FileNames {
    private const val MAX_LENGTH = 100
    private val forbidden = Regex("[/\\\\:*?\"<>|\\p{Cntrl}\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    /** A safe base name: no path separators, "..", control or bidi-override characters; never empty. */
    fun sanitize(name: String): String {
        val cleaned = name.replace(forbidden, "_")
            .replace("..", "_")
            .trim(' ', '.')
            .take(MAX_LENGTH)
            .trim(' ', '.')
        return cleaned.ifEmpty { "converted" }
    }

    /** Output display name for [input] converted to [format], or the user's [chosen] base name. */
    fun outputName(input: String?, format: Format, chosen: String? = null): String {
        val base = chosen?.takeIf { it.isNotBlank() }
            ?: input?.let { if ('.' in it) it.substringBeforeLast('.') else it }
            ?: "converted"
        return "${sanitize(base)}.${format.extension}"
    }
}
