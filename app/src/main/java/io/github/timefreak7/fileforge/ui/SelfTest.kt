package io.github.timefreak7.fileforge.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import io.github.timefreak7.fileforge.core.FileNames
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.Preset
import io.github.timefreak7.fileforge.core.Sniffer
import io.github.timefreak7.fileforge.image.ImageEngineClient
import io.github.timefreak7.fileforge.image.ImageEngineClient.Outcome
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Phase 1 gate checks that need a real phone, run from Settings in debug builds. Uses synthetic and
 * generated files only (spec: test corpus), in app cache; nothing is written to the output folder.
 */
class SelfTest(private val context: Context, private val report: (Boolean?, String) -> Unit) {
    private val dir = File(context.cacheDir, "selftest").apply { deleteRecursively(); mkdirs() }
    private val heic = ImageEngineClient.heicEncodeAvailable
    private val formats = Format.entries.filter { it != Format.HEIC || heic }

    suspend fun run(fuzzPerFormat: Int) {
        ImageEngineClient(context).use { engine ->
            conversions(engine)
            orientation(engine)
            metadata(engine)
            damagedFiles(engine)
            hundredPhotos(engine)
            fuzz(engine, fuzzPerFormat)
        }
        securityChecks()
        dir.deleteRecursively()
    }

    /** Every format to every format, reopened by a second reader with the expected size. */
    private suspend fun conversions(engine: ImageEngineClient) {
        for (from in formats) {
            val input = write(pattern(1200, 800, alpha = from == Format.PNG), from, "conv")
            val before = sha256(input)
            for (to in formats) {
                val out = File(dir, "out.${to.extension}")
                val outcome = engine.convert(input, from, out, to, Preset(maxSide = 0))
                val size = reopen(out)
                report(outcome is Outcome.Success && size == 1200 to 800, "${from.label} → ${to.label}: ${describe(outcome)}, reopened ${size?.let { "${it.first}×${it.second}" } ?: "failed"}")
            }
            val resized = File(dir, "small.jpg")
            engine.convert(input, from, resized, Format.JPG, Preset(maxSide = 600))
            report(reopen(resized) == 600 to 400, "${from.label} resized to 600 px: ${reopen(resized)}")
            report(sha256(input) == before, "${from.label} original unchanged after converting (hash)")
        }
    }

    /** Rotated photos come out upright; HEIC → JPG is the Phase 1 gate case. */
    private suspend fun orientation(engine: ImageEngineClient) {
        val jpeg = write(pattern(1200, 800), Format.JPG, "rot").also {
            ExifInterface(it).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        }
        val out = File(dir, "rot-out.png")
        engine.convert(jpeg, Format.JPG, out, Format.PNG, Preset())
        report(reopen(out) == 800 to 1200, "Rotated JPG → PNG keeps orientation: ${reopen(out)} (expect 800×1200)")
        if (heic) {
            val h = File(dir, "rot.heic")
            HeifWriter.Builder(h.absolutePath, 1200, 800, HeifWriter.INPUT_MODE_BITMAP).setRotation(90).build().use {
                it.start(); it.addBitmap(pattern(1200, 800)); it.stop(30_000)
            }
            val o = File(dir, "rot-heic.jpg")
            val outcome = engine.convert(h, Format.HEIC, o, Format.JPG, Preset())
            report(reopen(o) == 800 to 1200, "Rotated HEIC → JPG keeps orientation: ${describe(outcome)}, ${reopen(o)} (expect 800×1200)")
        } else {
            report(null, "No hardware HEVC encoder: HEIC output is hidden on this phone")
        }
    }

    /** Location is gone unless kept on purpose. */
    private suspend fun metadata(engine: ImageEngineClient) {
        val photo = write(pattern(800, 600), Format.JPG, "gps").also {
            ExifInterface(it).apply {
                setLatLong(48.8584, 2.2945)
                setAttribute(ExifInterface.TAG_MAKE, "TestCam")
                saveAttributes()
            }
        }
        for (to in listOf(Format.JPG, Format.WEBP, Format.PNG)) {
            val stripped = File(dir, "strip.${to.extension}")
            engine.convert(photo, Format.JPG, stripped, to, Preset(keepMetadata = false))
            report(ExifInterface(stripped).latLong == null, "${to.label}: location removed by default")
            val kept = File(dir, "keep.${to.extension}")
            val outcome = engine.convert(photo, Format.JPG, kept, to, Preset(keepMetadata = true))
            val latLong = ExifInterface(kept).latLong
            report(latLong != null && kotlin.math.abs(latLong[0] - 48.8584) < 0.001, "${to.label}: location kept when asked (${describe(outcome)})")
        }
    }

    /** Zero-byte, truncated and wrong-type files fail with a plain message; the app keeps working. */
    private suspend fun damagedFiles(engine: ImageEngineClient) {
        val good = write(pattern(1000, 700), Format.JPG, "good").readBytes()
        val cases = mapOf(
            "zero-byte" to ByteArray(0),
            "truncated JPG" to good.copyOf(good.size / 2),
            "header only" to good.copyOf(64),
            "random bytes" to Random(1).nextBytes(50_000),
            "PDF named .jpg" to "%PDF-1.7\n%âãÏÓ\n".toByteArray(),
        )
        for ((name, bytes) in cases) {
            val f = File(dir, "bad").apply { writeBytes(bytes) }
            val sniffed = Sniffer.sniff(bytes.copyOf(minOf(bytes.size, Sniffer.HEADER_BYTES)))
            if (sniffed == null) {
                report(true, "$name: rejected before converting (not a supported image)")
                continue
            }
            val outcome = engine.convert(f, sniffed, File(dir, "bad-out.png"), Format.PNG, Preset())
            report(outcome is Outcome.Failure, "$name: ${describe(outcome)}")
        }
    }

    /** Phase 1 gate: 100 mixed photos convert with no crash. */
    private suspend fun hundredPhotos(engine: ImageEngineClient) {
        val random = Random(100)
        var ok = 0
        val failures = mutableListOf<String>()
        val start = System.currentTimeMillis()
        repeat(100) { i ->
            val from = formats[i % formats.size]
            val w = random.nextInt(400, 4000)
            val h = random.nextInt(400, 3000)
            val input = write(pattern(w, h), from, "p")
            val outcome = engine.convert(input, from, File(dir, "p-out.jpg"), Format.JPG, Preset(quality = 85))
            if (outcome is Outcome.Success) ok++ else failures += "#$i ${from.label} ${w}×$h: ${describe(outcome)}"
        }
        val seconds = (System.currentTimeMillis() - start) / 1000
        report(ok == 100, "100 mixed photos → JPG: $ok converted in $seconds s" + failures.take(3).joinToString("") { "\n$it" })
    }

    /** Spec gate: mutated files through each converter; zero app crashes. */
    private suspend fun fuzz(engine: ImageEngineClient, perFormat: Int) {
        for (from in formats) {
            val seed = write(pattern(640, 480), from, "seed").readBytes()
            val random = Random(from.ordinal + 7)
            var converted = 0
            var rejected = 0
            var crashed = 0
            var timedOut = 0
            repeat(perFormat) {
                val bytes = seed.copyOf()
                repeat(random.nextInt(1, 20)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
                val data = if (random.nextInt(4) == 0) bytes.copyOf(random.nextInt(1, bytes.size)) else bytes
                val f = File(dir, "fuzz").apply { writeBytes(data) }
                val sniffed = Sniffer.sniff(data.copyOf(minOf(data.size, Sniffer.HEADER_BYTES)))
                if (sniffed == null) {
                    rejected++
                    return@repeat
                }
                when (val o = engine.convert(f, sniffed, File(dir, "fuzz-out.png"), Format.PNG, Preset())) {
                    is Outcome.Success -> converted++
                    is Outcome.Failure -> when {
                        "crashed" in o.message -> crashed++
                        "too long" in o.message -> timedOut++
                        else -> rejected++
                    }
                }
            }
            // Reaching this line means the app survived every file.
            report(true, "Fuzz ${from.label} × $perFormat: app survived. $converted converted, $rejected rejected cleanly, $crashed crashed the converter (recovered), $timedOut timed out")
        }
    }

    private fun securityChecks() {
        // The right-to-left override is built at run time so the source stays free of bidi characters.
        val hostile = listOf("../../etc/passwd", "a/b\\c", "..", "evil" + Char(0x202E) + "gpj.exe", "x\u0000y")
        report(
            hostile.all { FileNames.sanitize(it).let { s -> '/' !in s && '\\' !in s && ".." !in s && s.none(Char::isISOControl) } },
            "S7: hostile names sanitised",
        )
        val share = Outputs.shareIntent(Uri.parse("content://test/doc"), "image/jpeg")
        val inner = share.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        val readOnly = { i: Intent -> i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 }
        report(readOnly(share) && readOnly(inner), "S8: share grants read-only access to one file")
    }

    private fun write(bitmap: Bitmap, format: Format, name: String): File {
        val f = File(dir, "$name.${format.extension}")
        when (format) {
            Format.HEIC -> HeifWriter.Builder(f.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                .build().use { it.start(); it.addBitmap(bitmap); it.stop(30_000) }
            else -> f.outputStream().use {
                val cf = when (format) {
                    Format.JPG -> Bitmap.CompressFormat.JPEG
                    Format.PNG -> Bitmap.CompressFormat.PNG
                    else -> Bitmap.CompressFormat.WEBP_LOSSY
                }
                bitmap.compress(cf, 90, it)
            }
        }
        bitmap.recycle()
        return f
    }

    private fun pattern(w: Int, h: Int, alpha: Boolean = false): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b)
            c.drawColor(if (alpha) Color.TRANSPARENT else Color.rgb(14, 74, 92))
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.rgb(20, 184, 166)
            c.drawCircle(w / 2f, h / 2f, minOf(w, h) / 3f, p)
            p.color = Color.rgb(163, 230, 53)
            c.drawRect(0f, 0f, w / 4f, h / 4f, p)
        }

    private fun reopen(f: File): Pair<Int, Int>? = runCatching {
        var size: Pair<Int, Int>? = null
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
            size = info.size.width to info.size.height
            d.setTargetSize(1, 1)
        }
        size
    }.getOrNull()

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun describe(o: Outcome) = when (o) {
        is Outcome.Success -> "ok"
        is Outcome.Failure -> "\"${o.message}\""
    }
}
