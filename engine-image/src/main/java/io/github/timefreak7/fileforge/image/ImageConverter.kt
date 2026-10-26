package io.github.timefreak7.fileforge.image

import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.ParcelFileDescriptor
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
    private val KEPT_TAGS = listOf(
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

    /** Returns the output size. Throws [ConversionException] with a message for the user. */
    fun convert(
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
        target: Format,
        quality: Int,
        maxSide: Int,
        keepMetadata: Boolean,
    ): Pair<Int, Int> {
        val bitmap = decode(input, maxSide)
        try {
            when (target) {
                Format.JPG -> compress(withWhiteBackground(bitmap), Bitmap.CompressFormat.JPEG, quality, output)
                Format.PNG -> compress(bitmap, Bitmap.CompressFormat.PNG, 100, output)
                Format.WEBP -> compress(bitmap, Bitmap.CompressFormat.WEBP_LOSSY, quality, output)
                Format.HEIC -> encodeHeic(bitmap, quality, output)
            }
            // HEIC output: ExifInterface cannot write HEIF, so metadata is always dropped there.
            if (keepMetadata && target != Format.HEIC) copyMetadata(input, output)
            return bitmap.width to bitmap.height
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(input: ParcelFileDescriptor, maxSide: Int): Bitmap {
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

    private fun copyMetadata(input: ParcelFileDescriptor, output: ParcelFileDescriptor) {
        try {
            val source = input.dup().use { ExifInterface(it.fileDescriptor) }
            val target = ExifInterface(output.fileDescriptor)
            for (tag in KEPT_TAGS) source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.saveAttributes()
        } catch (e: Exception) {
            throw ConversionException("The image converted, but its metadata could not be copied.")
        }
    }
}
