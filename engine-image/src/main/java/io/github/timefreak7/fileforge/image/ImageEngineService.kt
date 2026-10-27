package io.github.timefreak7.fileforge.image

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import io.github.timefreak7.fileforge.core.Format

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
        ): Bundle = try {
            val target = Format.fromMime(targetMime) ?: throw ConversionException("Unknown output format.")
            val (w, h) = ImageConverter.convert(input, output, target, quality, maxSide)
            Bundle().apply {
                putBoolean("ok", true)
                putInt("w", w)
                putInt("h", h)
                // HEIC output: ExifInterface cannot write HEIF, so metadata is always dropped there.
                if (keepMetadata && target != Format.HEIC) {
                    putBundle("exif", Bundle().apply { ImageConverter.readMetadata(input).forEach { (tag, value) -> putString(tag, value) } })
                }
            }
        } catch (e: ConversionException) {
            failure(e.message ?: "Conversion failed.")
        } catch (t: Throwable) {
            failure("Conversion failed (${t.javaClass.simpleName}).")
        } finally {
            input.close()
            output.close()
        }
    }

    private fun failure(message: String) = Bundle().apply {
        putBoolean("ok", false)
        putString("error", message)
    }

    override fun onBind(intent: Intent): IBinder = binder
}

/** S4: android:isolatedProcess, no storage or network rights. Decodes JPG, PNG and WebP. */
class IsolatedImageService : ImageEngineService()

/**
 * Separate ":media" process with normal rights but no file access beyond its descriptors.
 * Handles HEIC in and out: Phase 0 showed hardware codecs are unreachable from an isolated process.
 */
class MediaImageService : ImageEngineService()
