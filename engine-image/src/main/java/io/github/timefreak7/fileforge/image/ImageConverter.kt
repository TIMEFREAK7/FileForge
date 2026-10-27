package io.github.timefreak7.fileforge.image

import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.ColorSpace
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.Limits
import java.io.FileOutputStream

/** Plain-language failure: shown to the user as is. */
class ConversionException(message: String) : Exception(message)

/** Runs inside a parser process (isolated or ":media"). Sees only the descriptors it is handed. */
object ImageConverter {

    /** EXIF tags carried over when the user keeps metadata. Orientation is not: pixels are already upright. */
    val KEPT_TAGS = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    /** Output size, plus stage timings for the self-test's speed check. */
    class Result(val width: Int, val height: Int, val decodeMs: Long, val encodeMs: Long)

    /** Throws [ConversionException] with a message for the user. */
    fun convert(
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
        target: Format,
        quality: Int,
        maxSide: Int,
    ): Result {
        val t0 = SystemClock.elapsedRealtime()
        val bitmap = decode(input, maxSide)
        val t1 = SystemClock.elapsedRealtime()
        try {
            encode(bitmap, target, quality, output)
            return Result(bitmap.width, bitmap.height, t1 - t0, SystemClock.elapsedRealtime() - t1)
        } finally {
            bitmap.recycle()
        }
    }

    /** Encodes pixels FileForge already decoded; never parses untrusted bytes. */
    fun encode(bitmap: Bitmap, target: Format, quality: Int, output: ParcelFileDescriptor) {
        when (target) {
            Format.JPG -> compress(withWhiteBackground(bitmap), Bitmap.CompressFormat.JPEG, quality, output)
            Format.PNG -> compress(bitmap, Bitmap.CompressFormat.PNG, 100, output)
            Format.WEBP -> compress(bitmap, Bitmap.CompressFormat.WEBP_LOSSY, quality, output)
            Format.HEIC -> encodeHeic(bitmap, quality, output)
            Format.PDF -> throw ConversionException("PDF output is made by the PDF engine.")
        }
    }

    /** Pixels for another process: a binder transaction is capped near 1 MB, so they travel in shared memory. */
    fun toPixels(decoded: Bitmap): Bundle {
        // HDR and 10-bit photos decode to wider pixel formats; the receiver rebuilds ARGB_8888.
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) decoded else decoded.copy(Bitmap.Config.ARGB_8888, false)
        val mem = SharedMemory.create("pixels", bitmap.byteCount)
        val buffer = mem.mapReadWrite()
        bitmap.copyPixelsToBuffer(buffer)
        SharedMemory.unmap(buffer)
        mem.setProtect(OsConstants.PROT_READ)
        val pixels = Bundle().apply {
            putParcelable("mem", mem)
            putInt("w", bitmap.width)
            putInt("h", bitmap.height)
            putBoolean("alpha", bitmap.hasAlpha())
            putString("cs", (namedColorSpace(bitmap.colorSpace) ?: ColorSpace.Named.SRGB).name)
        }
        if (bitmap !== decoded) bitmap.recycle()
        return pixels
    }

    fun fromPixels(pixels: Bundle): Bitmap {
        val w = pixels.getInt("w")
        val h = pixels.getInt("h")
        val colorSpace = ColorSpace.get(ColorSpace.Named.valueOf(pixels.getString("cs")!!))
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, pixels.getBoolean("alpha"), colorSpace)
        pixels.getParcelable("mem", SharedMemory::class.java)!!.use { mem ->
            require(mem.size >= bitmap.byteCount) { "pixel buffer too small" }
            val buffer = mem.mapReadOnly()
            bitmap.copyPixelsFromBuffer(buffer)
            SharedMemory.unmap(buffer)
        }
        return bitmap
    }

    private fun namedColorSpace(cs: ColorSpace?): ColorSpace.Named? =
        ColorSpace.Named.entries.firstOrNull { ColorSpace.get(it) == (cs ?: ColorSpace.get(ColorSpace.Named.SRGB)) }

    fun decode(input: ParcelFileDescriptor, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource {
            AssetFileDescriptor(input.dup(), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (w.toLong() * h > Limits.MAX_IMAGE_PIXELS) {
                    throw ConversionException(
                        "This image is ${w.toLong() * h / 1_000_000} megapixels; the limit is ${Limits.MAX_IMAGE_PIXELS / 1_000_000}.",
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Pixels may cross to another process, which needs a colour space it can name; ICC-only ones become sRGB.
                if (namedColorSpace(info.colorSpace) == null) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
                val longest = maxOf(w, h)
                if (maxSide in 1 until longest) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                }
            }
        } catch (e: ConversionException) {
            throw e
        } catch (e: ImageDecoder.DecodeException) {
            throw ConversionException("This file is damaged or is not an image FileForge can read.")
        } catch (e: OutOfMemoryError) {
            throw ConversionException("This image is too large to convert on this phone. Try a smaller size.")
        }
    }

    /** JPEG has no transparency: transparent areas become white instead of black. */
    private fun withWhiteBackground(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return flat
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int, output: ParcelFileDescriptor) {
        // Not closed here: the stream does not own the descriptor, the service closes it.
        val stream = FileOutputStream(output.fileDescriptor)
        val ok = bitmap.compress(format, quality, stream)
        stream.flush()
        if (!ok) throw ConversionException("The image could not be encoded.")
    }

    private fun encodeHeic(bitmap: Bitmap, quality: Int, output: ParcelFileDescriptor) {
        try {
            HeifWriter.Builder(output.fileDescriptor, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(quality)
                .build()
                .use { writer ->
                    writer.start()
                    writer.addBitmap(bitmap)
                    writer.stop(60_000)
                }
        } catch (e: Exception) {
            throw ConversionException("This phone could not encode HEIC (${e.javaClass.simpleName}).")
        }
    }

    /**
     * Reads the kept EXIF tags from the input. Writing them happens in the main process
     * ([ImageEngineClient]): ExifInterface saves through a temp file, which an isolated process cannot create.
     */
    fun readMetadata(input: ParcelFileDescriptor): Map<String, String> = try {
        val exif = input.dup().use { ExifInterface(it.fileDescriptor) }
        KEPT_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
    } catch (e: Exception) {
        throw ConversionException("The image converted, but its photo details could not be read.")
    }
}
