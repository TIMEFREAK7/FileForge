package io.github.timefreak7.fileforge.image

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.os.SystemClock
import io.github.timefreak7.fileforge.core.Format
import java.io.File

/** The image engine's binder. Declared twice in the manifest so it runs in two kinds of process. */
abstract class ImageEngineService : Service() {
    private val binder = object : IImageEngine.Stub() {
        override fun pid(): Int = Process.myPid()

        override fun convert(
            input: ParcelFileDescriptor,
            output: ParcelFileDescriptor,
            targetMime: String,
            quality: Int,
            maxSide: Int,
            keepMetadata: Boolean,
        ): Bundle = safely(input, output) {
            val target = target(targetMime)
            val result = ImageConverter.convert(input, output, target, quality, maxSide)
            putInt("w", result.width)
            putInt("h", result.height)
            putLong("decodeMs", result.decodeMs)
            putLong("encodeMs", result.encodeMs)
            // HEIC output: ExifInterface cannot write HEIF, so metadata is always dropped there.
            if (keepMetadata && target != Format.HEIC) putBundle("exif", exif(input))
        }

        override fun decode(input: ParcelFileDescriptor, maxSide: Int, keepMetadata: Boolean): Bundle = safely(input) {
            val start = SystemClock.elapsedRealtime()
            val bitmap = ImageConverter.decode(input, maxSide)
            try {
                putAll(ImageConverter.toPixels(bitmap))
            } finally {
                bitmap.recycle()
            }
            putLong("decodeMs", SystemClock.elapsedRealtime() - start)
            if (keepMetadata) putBundle("exif", exif(input))
        }

        override fun encode(pixels: Bundle, output: ParcelFileDescriptor, targetMime: String, quality: Int): Bundle =
            safely(output) {
                val start = SystemClock.elapsedRealtime()
                pixels.classLoader = SharedMemory::class.java.classLoader
                val bitmap = ImageConverter.fromPixels(pixels)
                try {
                    ImageConverter.encode(bitmap, target(targetMime), quality, output)
                } finally {
                    bitmap.recycle()
                }
                putLong("encodeMs", SystemClock.elapsedRealtime() - start)
            }
    }

    private fun target(mime: String) = Format.fromMime(mime) ?: throw ConversionException("Unknown output format.")

    private fun exif(input: ParcelFileDescriptor) =
        Bundle().apply { ImageConverter.readMetadata(input).forEach { (tag, value) -> putString(tag, value) } }

    /** Runs one call, turns any failure into a plain-language "error", and always closes the descriptors. */
    private fun safely(vararg fds: ParcelFileDescriptor, block: Bundle.() -> Unit): Bundle = try {
        val start = SystemClock.elapsedRealtime()
        Bundle().apply {
            block()
            putBoolean("ok", true)
            // Diagnostics for the self-test's speed check: time spent here, and the CPU group Android gave us.
            putLong("ms", SystemClock.elapsedRealtime() - start)
            putString("cpuset", runCatching { File("/proc/self/cpuset").readText().trim() }.getOrDefault("unknown"))
        }
    } catch (e: ConversionException) {
        failure(e.message ?: "Conversion failed.")
    } catch (t: Throwable) {
        failure("Conversion failed (${t.javaClass.simpleName}).")
    } finally {
        fds.forEach { it.close() }
    }

    private fun failure(message: String) = Bundle().apply {
        putBoolean("ok", false)
        putString("error", message)
    }

    override fun onBind(intent: Intent): IBinder = binder
}

/** S4: android:isolatedProcess, no storage or network rights. Decodes (parses) JPG, PNG and WebP. */
class IsolatedImageService : ImageEngineService()

/**
 * Separate ":media" process with normal rights but no file access beyond its descriptors. Decodes HEIC
 * and does all encoding: Phase 0 showed hardware codecs are unreachable from an isolated process, and
 * the Phase 1 speed check showed the platform image encoder stalls ~5 s per image there.
 */
class MediaImageService : ImageEngineService()
