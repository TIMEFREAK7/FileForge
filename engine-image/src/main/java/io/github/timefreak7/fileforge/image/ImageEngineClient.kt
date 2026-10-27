package io.github.timefreak7.fileforge.image

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import androidx.exifinterface.media.ExifInterface
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.Limits
import io.github.timefreak7.fileforge.core.Preset
import java.io.Closeable
import java.io.File

/** Main-process side of the image engine: routes each step to the right parser process. */
class ImageEngineClient(context: Context) : Closeable {

    sealed interface Outcome {
        /** [engineMs], [decodeMs], [encodeMs] and [cpuset] are diagnostics for the self-test's speed check. */
        data class Success(
            val width: Int,
            val height: Int,
            val engineMs: Long = 0,
            val decodeMs: Long = 0,
            val encodeMs: Long = 0,
            val cpuset: String = "",
        ) : Outcome
        data class Failure(val message: String) : Outcome
    }

    private val calls = ParserCalls(context, IImageEngine.Stub::asInterface) { it.pid() }
    private val media = MediaImageService::class.java

    /**
     * Converts [input] (detected as [inputFormat]) into [output]. Never throws for a bad file.
     * JPG/PNG/WebP are parsed in the isolated process and encoded in ":media"; HEIC input goes wholly
     * to ":media". [inMediaProcess] forces the whole conversion into ":media" (self-test speed check).
     */
    suspend fun convert(
        input: File,
        inputFormat: Format,
        output: File,
        target: Format,
        preset: Preset,
        inMediaProcess: Boolean = false,
    ): Outcome {
        val timeout = Limits.imageTimeoutMs(input.length())
        return try {
            val (result, exif) = if (inMediaProcess || inputFormat == Format.HEIC) {
                val r = calls.call(media, timeout, true, readOnly(input), writable(output)) { e, (i, o) ->
                    e.convert(i, o, target.mime, preset.quality, preset.maxSide, preset.keepMetadata)
                }
                r to r.getBundle("exif")
            } else {
                val pixels = calls.call(IsolatedImageService::class.java, timeout, false, readOnly(input)) { e, (i) ->
                    e.decode(i, preset.maxSide, preset.keepMetadata && target != Format.HEIC)
                }
                try {
                    val encoded = encodeRaw(pixels, output, target, preset.quality, timeout)
                    Bundle(pixels).apply { putAll(encoded) } to pixels.getBundle("exif")
                } finally {
                    pixels.getParcelable("mem", SharedMemory::class.java)?.close()
                }
            }
            exif?.let { writeExif(output, it) }
            Outcome.Success(
                result.getInt("w"),
                result.getInt("h"),
                result.getLong("ms"),
                result.getLong("decodeMs"),
                result.getLong("encodeMs"),
                result.getString("cpuset").orEmpty(),
            )
        } catch (e: ParserFailure) {
            Outcome.Failure(e.message ?: "Conversion failed.")
        }
    }

    /**
     * Encodes pixels another parser process produced (for example a rendered PDF page) in ":media".
     * Throws [ParserFailure]. The caller keeps ownership of the pixels' shared memory.
     */
    suspend fun encode(pixels: Bundle, output: File, target: Format, quality: Int) {
        encodeRaw(pixels, output, target, quality, Limits.imageTimeoutMs(0))
    }

    private suspend fun encodeRaw(pixels: Bundle, output: File, target: Format, quality: Int, timeout: Long) =
        calls.call(media, timeout, true, writable(output)) { e, (o) -> e.encode(pixels, o, target.mime, quality) }

    /** Writes only into our own freshly encoded output; the untrusted input was parsed remotely. */
    private fun writeExif(output: File, tags: Bundle) {
        try {
            ExifInterface(output).apply {
                ImageConverter.KEPT_TAGS.forEach { tag -> tags.getString(tag)?.let { setAttribute(tag, it) } }
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
        } catch (e: Exception) {
            throw ParserFailure("The image converted, but its photo details could not be saved.")
        }
    }

    override fun close() = calls.close()

    companion object {
        fun readOnly(f: File): ParcelFileDescriptor = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)

        fun writable(f: File): ParcelFileDescriptor = ParcelFileDescriptor.open(
            f,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
        )

        /** HEIC output needs a hardware HEVC encoder; checked once, in the main process. */
        val heicEncodeAvailable: Boolean by lazy {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                it.isEncoder && it.isHardwareAccelerated && MediaFormat.MIMETYPE_VIDEO_HEVC in it.supportedTypes
            }
        }
    }
}
