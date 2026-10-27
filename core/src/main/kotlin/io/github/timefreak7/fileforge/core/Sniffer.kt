package io.github.timefreak7.fileforge.core

/** Identifies an image by its first bytes, so a wrong extension or MIME type cannot misroute a file. */
object Sniffer {
    const val HEADER_BYTES = 16
    private val heifBrands = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "heif")

    fun sniff(header: ByteArray): Format? {
        fun at(i: Int) = header.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        fun ascii(from: Int, len: Int) =
            if (header.size >= from + len) String(header, from, len, Charsets.ISO_8859_1) else ""
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> Format.JPG
            at(0) == 0x89 && ascii(1, 3) == "PNG" -> Format.PNG
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> Format.WEBP
            ascii(4, 4) == "ftyp" && ascii(8, 4) in heifBrands -> Format.HEIC
            // shortcut: header only at byte 0; readers also accept it within the first 1 KB. Widen if real files need it.
            ascii(0, 5) == "%PDF-" -> Format.PDF
            else -> null
        }
    }
}
