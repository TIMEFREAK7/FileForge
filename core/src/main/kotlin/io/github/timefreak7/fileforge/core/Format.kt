package io.github.timefreak7.fileforge.core

/** File formats FileForge reads and writes in v1. */
enum class Format(val mime: String, val extension: String, val label: String) {
    JPG("image/jpeg", "jpg", "JPG"),
    PNG("image/png", "png", "PNG"),
    WEBP("image/webp", "webp", "WebP"),
    HEIC("image/heic", "heic", "HEIC"),
    PDF("application/pdf", "pdf", "PDF");

    /** PNG is lossless, so the quality setting does not apply. For PDF it is the image quality when compressing. */
    val hasQuality: Boolean get() = this != PNG

    val isImage: Boolean get() = this != PDF

    companion object {
        fun fromMime(mime: String?): Format? = when (mime?.lowercase()) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> JPG
            "image/png" -> PNG
            "image/webp" -> WEBP
            "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence" -> HEIC
            "application/pdf" -> PDF
            else -> null
        }

        fun fromExtension(name: String?): Format? = when (name?.substringAfterLast('.', "")?.lowercase()) {
            "jpg", "jpeg", "jpe" -> JPG
            "png" -> PNG
            "webp" -> WEBP
            "heic", "heif", "hif" -> HEIC
            "pdf" -> PDF
            else -> null
        }

        /** Default donut order: the most common targets first. */
        val DEFAULT_ORDER = listOf(JPG, PNG, WEBP, HEIC, PDF)
    }
}

/** Page size for images → PDF. FIT makes each page the image's own shape. */
enum class PageSize(val label: String, val widthPt: Float, val heightPt: Float) {
    A4("A4", 595f, 842f),
    LETTER("Letter", 612f, 792f),
    FIT("Fit image", 0f, 0f),
}

/** Per-conversion settings chosen in the options sheet or taken from the defaults. */
data class Preset(
    val quality: Int = 90,
    /** Longest side in pixels; 0 keeps the original size. */
    val maxSide: Int = 0,
    val keepMetadata: Boolean = false,
    /** Output base name without extension; null derives it from the input name. */
    val outputName: String? = null,
    /** PDF → images: render resolution. PDF compress: the most resolution an embedded image keeps. */
    val dpi: Int = 150,
    val pageSize: PageSize = PageSize.A4,
) {
    init {
        require(quality in 1..100) { "quality must be 1..100" }
        require(maxSide >= 0) { "maxSide must be >= 0" }
        require(dpi in 36..600) { "dpi must be 36..600" }
    }
}
