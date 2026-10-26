package io.github.timefreak7.fileforge.core

/** Image formats FileForge reads and writes in v1. */
enum class Format(val mime: String, val extension: String, val label: String) {
    JPG("image/jpeg", "jpg", "JPG"),
    PNG("image/png", "png", "PNG"),
    WEBP("image/webp", "webp", "WebP"),
    HEIC("image/heic", "heic", "HEIC");

    /** PNG is lossless, so the quality setting does not apply. */
    val hasQuality: Boolean get() = this != PNG

    companion object {
        fun fromMime(mime: String?): Format? = when (mime?.lowercase()) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> JPG
            "image/png" -> PNG
            "image/webp" -> WEBP
            "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence" -> HEIC
            else -> null
        }

        fun fromExtension(name: String?): Format? = when (name?.substringAfterLast('.', "")?.lowercase()) {
            "jpg", "jpeg", "jpe" -> JPG
            "png" -> PNG
            "webp" -> WEBP
            "heic", "heif", "hif" -> HEIC
            else -> null
        }

        /** Default donut order: the most common targets first. */
        val DEFAULT_ORDER = listOf(JPG, PNG, WEBP, HEIC)
    }
}

/** Per-conversion settings chosen in the options sheet or taken from the defaults. */
data class Preset(
    val quality: Int = 90,
    /** Longest side in pixels; 0 keeps the original size. */
    val maxSide: Int = 0,
    val keepMetadata: Boolean = false,
    /** Output base name without extension; null derives it from the input name. */
    val outputName: String? = null,
) {
    init {
        require(quality in 1..100) { "quality must be 1..100" }
        require(maxSide >= 0) { "maxSide must be >= 0" }
    }
}
