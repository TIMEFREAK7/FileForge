package io.github.timefreak7.fileforge.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import io.github.timefreak7.fileforge.core.FileNames
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.PageSize
import io.github.timefreak7.fileforge.core.Preset
import io.github.timefreak7.fileforge.core.Sniffer
import io.github.timefreak7.fileforge.image.ImageConverter
import io.github.timefreak7.fileforge.image.ImageEngineClient
import io.github.timefreak7.fileforge.image.ImageEngineClient.Outcome
import io.github.timefreak7.fileforge.image.ParserFailure
import io.github.timefreak7.fileforge.jobs.AppSettings
import io.github.timefreak7.fileforge.jobs.IncomingFile
import io.github.timefreak7.fileforge.jobs.JobQueue
import io.github.timefreak7.fileforge.jobs.JobRecord
import io.github.timefreak7.fileforge.jobs.JobStore
import io.github.timefreak7.fileforge.pdf.PageRef
import io.github.timefreak7.fileforge.pdf.PdfEngineClient
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Phase 1 and 2 gate checks that need a real phone, run from Settings in debug builds. Uses synthetic and
 * generated files only (spec: test corpus), in app cache; nothing is written to the output folder.
 */
class SelfTest(
    private val context: Context,
    private val report: (Boolean?, String) -> Unit,
    /** Called after every unit of work: (done, total, current step). */
    private val progress: (Int, Int, String) -> Unit,
) {
    private val dir = File(context.cacheDir, "selftest").apply { deleteRecursively(); mkdirs() }
    private val heic = ImageEngineClient.heicEncodeAvailable
    private val formats = Format.entries.filter { it.isImage && (it != Format.HEIC || heic) }
    private var done = 0
    private var total = 0
    private var step = ""

    private fun tick() = progress(++done, total, step)

    suspend fun run(fuzzPerFormat: Int) {
        val n = formats.size
        total = n * (n + 2) + 2 + 6 + 5 + SPEED_RUNS * 4 + PHOTOS + fuzzPerFormat * n + 2 + PDF_TICKS + fuzzPerFormat
        ImageEngineClient(context).use { engine -> PdfEngineClient(context).use { pdf ->
            section("Converting every format") { conversions(engine) }
            section("Rotated photos") { orientation(engine) }
            section("Photo details") { metadata(engine) }
            section("Damaged files") { damagedFiles(engine) }
            section("Speed check") { speed(engine) }
            section("100 mixed photos") { hundredPhotos(engine) }
            section("Fuzz pass") { fuzz(engine, fuzzPerFormat) }
            section("PDF page tools") { pageTools(pdf) }
            section("PDF ↔ images") { pdfAndImages(engine, pdf) }
            section("Damaged PDFs") { damagedPdfs(pdf) }
            section("300-page PDF") { bigPdf(pdf) }
            section("PDF compression") { compression(pdf) }
            section("PDF fuzz pass") { fuzzPdf(pdf, fuzzPerFormat) }
            section("Protected PDFs") { passwords(pdf) }
        } }
        section("Security checks") { securityChecks() }
        dir.deleteRecursively()
        progress(total, total, "Done")
    }

    /** One section failing (an exception, not a failed check) is reported and the rest still run. */
    private suspend fun section(name: String, block: suspend () -> Unit) {
        step = name
        progress(done, total, step)
        try {
            block()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            report(false, "$name stopped early: ${t.javaClass.simpleName}: ${t.message}")
        }
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
                tick()
            }
            val resized = File(dir, "small.jpg")
            engine.convert(input, from, resized, Format.JPG, Preset(maxSide = 600))
            report(reopen(resized) == 600 to 400, "${from.label} resized to 600 px: ${reopen(resized)}")
            tick()
            report(sha256(input) == before, "${from.label} original unchanged after converting (hash)")
            tick()
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
        tick()
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
        tick()
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
            tick()
            val kept = File(dir, "keep.${to.extension}")
            val outcome = engine.convert(photo, Format.JPG, kept, to, Preset(keepMetadata = true))
            val latLong = ExifInterface(kept).latLong
            report(latLong != null && kotlin.math.abs(latLong[0] - 48.8584) < 0.001, "${to.label}: location kept when asked (${describe(outcome)})")
            tick()
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
            } else {
                val outcome = engine.convert(f, sniffed, File(dir, "bad-out.png"), Format.PNG, Preset())
                report(outcome is Outcome.Failure, "$name: ${describe(outcome)}")
            }
            tick()
        }
    }

    /**
     * The same 12 MP JPG → JPG conversion timed four ways, split into decode and encode, to find
     * where the time goes: the isolated process, the ":media" process, the converter's own code run in
     * the app, and a plain decode/encode in the app.
     */
    private suspend fun speed(engine: ImageEngineClient) {
        val input = write(pattern(4000, 3000), Format.JPG, "speed")
        val out = File(dir, "speed-out.jpg")
        suspend fun remote(media: Boolean): String {
            var cpuset = "unknown"
            val parts = (1..SPEED_RUNS).map {
                val o = engine.convert(input, Format.JPG, out, Format.JPG, Preset(), inMediaProcess = media)
                tick()
                (o as? Outcome.Success)?.also { s -> cpuset = s.cpuset }?.let { s -> "${s.decodeMs}+${s.encodeMs}" } ?: "failed"
            }
            return "${parts.joinToString(" / ")} ms ($cpuset)"
        }
        val isolated = remote(media = false)
        val media = remote(media = true)
        // Debug-only comparisons on our own generated file; the app never decodes user files itself.
        val sameCode = (1..SPEED_RUNS).map {
            val r = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { inFd ->
                ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE).use { outFd ->
                    ImageConverter.convert(inFd, outFd, Format.JPG, 90, 0)
                }
            }
            tick()
            "${r.decodeMs}+${r.encodeMs}"
        }
        val plain = (1..SPEED_RUNS).map {
            val t0 = System.currentTimeMillis()
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            val t1 = System.currentTimeMillis()
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            tick()
            "${t1 - t0}+${System.currentTimeMillis() - t1}"
        }
        report(
            null,
            "Speed, 12 MP JPG → JPG, decode+encode ms:\n" +
                "normal path (decode isolated, encode :media): $isolated\n" +
                "all in :media: $media\n" +
                "converter code in the app: ${sameCode.joinToString(" / ")}\n" +
                "plain in the app: ${plain.joinToString(" / ")}",
        )
    }

    /** Phase 1 gate: 100 mixed photos convert with no crash. */
    private suspend fun hundredPhotos(engine: ImageEngineClient) {
        val random = Random(100)
        var ok = 0
        val failures = mutableListOf<String>()
        val start = System.currentTimeMillis()
        var convertMs = 0L
        repeat(PHOTOS) { i ->
            val from = formats[i % formats.size]
            // Even sizes: some HEVC encoders reject odd dimensions when the test files are made.
            val w = random.nextInt(200, 2000) * 2
            val h = random.nextInt(200, 1500) * 2
            step = "100 mixed photos: #${i + 1} ${from.label} ${w}×$h"
            try {
                val input = write(pattern(w, h), from, "p")
                val t0 = System.currentTimeMillis()
                val outcome = engine.convert(input, from, File(dir, "p-out.jpg"), Format.JPG, Preset(quality = 85))
                convertMs += System.currentTimeMillis() - t0
                if (outcome is Outcome.Success) ok++ else failures += "#${i + 1} ${from.label} ${w}×$h: ${describe(outcome)}"
            } catch (e: Exception) {
                failures += "#${i + 1} ${from.label} ${w}×$h: test file could not be made (${e.javaClass.simpleName})"
            }
            tick()
        }
        val seconds = (System.currentTimeMillis() - start) / 1000
        report(
            ok == PHOTOS,
            "100 mixed photos → JPG: $ok converted; ${convertMs / 1000} s converting, $seconds s including making the test files" +
                failures.take(3).joinToString("") { "\n$it" },
        )
    }

    /** Spec gate: mutated files through each converter; zero app crashes. */
    private suspend fun fuzz(engine: ImageEngineClient, perFormat: Int) {
        for (from in formats) {
            step = "Fuzz pass: ${from.label}"
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
                    tick()
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
                tick()
            }
            // Reaching this line means the app survived every file.
            report(true, "Fuzz ${from.label} × $perFormat: app survived. $converted converted, $rejected rejected cleanly, $crashed crashed the converter (recovered), $timedOut timed out")
        }
    }

    /** Rotate, delete, reorder and extract are all one assemble call; coloured pages show the order survived. */
    private suspend fun pageTools(pdf: PdfEngineClient) {
        val colours = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA)
        val source = makePdf(colours.size, "colours") { colours[it] }
        report(pdf.pageCount(source) == 5, "Made a 5-page test PDF")
        tick()
        val out = File(dir, "tools.pdf")
        val pages = pdf.assemble(listOf(source), listOf(PageRef(0, 4), PageRef(0, 0, 90), PageRef(0, 2)), out)
        report(pages == 3 && pdf.pageCount(out) == 3, "Delete + reorder: 5 pages → $pages")
        tick()
        val order = (0 until 3).map { centre(pdf.renderBitmap(out, it, 100)) }
        val expected = listOf(Color.MAGENTA, Color.RED, Color.BLUE)
        report(order.zip(expected).all { (a, b) -> close(a, b) }, "Reorder kept the right pages (page colours checked by Android's PDF renderer)")
        tick()
        val turned = pdf.renderBitmap(out, 1, 100)
        report(turned.width > turned.height, "Rotate: page 2 is landscape after a 90° turn (${turned.width}×${turned.height})")
        tick()
    }

    /** PDF → JPG/PNG at 150 dpi, and photos → one A4 PDF, each reopened by a different reader than wrote it. */
    private suspend fun pdfAndImages(engine: ImageEngineClient, pdf: PdfEngineClient) {
        val source = makePdf(2, "pages") { Color.WHITE }
        for (to in listOf(Format.JPG, Format.PNG)) {
            val pixels = pdf.renderPixels(source, 0, 150)
            val out = File(dir, "page.${to.extension}")
            try {
                engine.encode(pixels, out, to, 90)
            } finally {
                pixels.getParcelable("mem", SharedMemory::class.java)?.close()
            }
            val size = reopen(out)
            report(size != null && kotlin.math.abs(size.first - 1240) <= 2 && kotlin.math.abs(size.second - 1754) <= 2, "PDF page → ${to.label} at 150 dpi: $size (expect about 1240×1754)")
            tick()
        }
        val photos = listOf(pattern(1200, 800), pattern(800, 1200), pattern(1000, 1000)).mapIndexed { i, b -> write(b, Format.JPG, "photo$i") }
        val out = File(dir, "photos.pdf")
        val pages = pdf.imagesToPdf(photos, out, PageSize.A4)
        val first = pdf.renderBitmap(out, 0, 200)
        report(pages == 3 && pdf.pageCount(out) == 3 && first.width > first.height, "3 photos → A4 PDF: $pages pages; first page landscape (${first.width}×${first.height})")
        tick()
    }

    /** Broken PDFs fail with a plain message (or open repaired); none takes the app down. */
    private suspend fun damagedPdfs(pdf: PdfEngineClient) {
        val good = makePdf(3, "good") { Color.WHITE }.readBytes()
        val cases = mapOf(
            "header only" to "%PDF-1.7\n".toByteArray(),
            "truncated" to good.copyOf(good.size / 2),
            "random after header" to "%PDF-1.4\n".toByteArray() + Random(3).nextBytes(20_000),
            "no cross-reference table" to good.copyOf(good.size - 200),
        )
        for ((name, bytes) in cases) {
            val f = File(dir, "bad.pdf").apply { writeBytes(bytes) }
            val result = try {
                "opened (repaired), ${pdf.pageCount(f)} pages"
            } catch (e: ParserFailure) {
                "\"${e.message}\""
            }
            report("crashed" !in result && "too long" !in result, "$name PDF: $result")
            tick()
        }
    }

    /** Phase 2 gate: a 300-page PDF merges and splits without running out of memory. */
    private suspend fun bigPdf(pdf: PdfEngineClient) {
        val big = makePdf(300, "big") { Color.WHITE }
        val t0 = System.currentTimeMillis()
        val merged = File(dir, "merged.pdf")
        val pages = pdf.assemble(listOf(big, big), List(600) { PageRef(it / 300, it % 300) }, merged)
        val mergeMs = System.currentTimeMillis() - t0
        report(pages == 600 && pdf.pageCount(merged) == 600, "Merge 300 + 300 pages: $pages pages in $mergeMs ms (${big.length() / 1024} KB each)")
        merged.delete()
        tick()
        val t1 = System.currentTimeMillis()
        val part = File(dir, "part.pdf")
        val counts = (0 until 10).map { k ->
            step = "300-page PDF: split part ${k + 1} of 10"
            pdf.assemble(listOf(big), List(30) { PageRef(0, k * 30 + it) }, part)
            pdf.pageCount(part)
        }
        report(counts.all { it == 30 }, "Split 300 pages into 10 files of 30: ${counts.sum()} pages in ${System.currentTimeMillis() - t1} ms")
        tick()
    }

    /** Phase 2 gate: a scanned PDF comes out at least 40% smaller and still readable. */
    private suspend fun compression(pdf: PdfEngineClient) {
        val (bitmapMs, yuvMs) = pdf.jpegProbe()
        report(null, "JPEG encode in the isolated process, 12 MP: Bitmap.compress $bitmapMs ms, YuvImage $yuvMs ms")
        tick()
        val scans = (1..3).map { write(scanPage(it), Format.JPG, "scan$it") }
        val scanned = File(dir, "scanned.pdf")
        pdf.imagesToPdf(scans, scanned, PageSize.FIT)
        scans.forEach { it.delete() }
        val small = File(dir, "small.pdf")
        val t0 = System.currentTimeMillis()
        val defaults = AppSettings(context).defaultPreset(Format.PDF)
        val replaced = pdf.compress(scanned, small, defaults.quality, defaults.dpi, 3)
        val ms = System.currentTimeMillis() - t0
        val saved = 100 - small.length() * 100 / scanned.length()
        val diff = meanDifference(pdf.renderBitmap(scanned, 0, 600), pdf.renderBitmap(small, 0, 600))
        report(
            saved >= 40 && diff < 8 && pdf.pageCount(small) == 3,
            "Scanned PDF (3 pages, 300 dpi): ${scanned.length() / 1024} KB → ${small.length() / 1024} KB, $saved% smaller, " +
                "$replaced images replaced in $ms ms; page difference $diff of 255 (readable under 8)",
        )
        tick()
    }

    /** Spec gate: mutated PDFs through every PDF reader (PDFBox open, Android render, compress). */
    private suspend fun fuzzPdf(pdf: PdfEngineClient, count: Int) {
        val photos = listOf(pattern(640, 480), pattern(480, 640)).mapIndexed { i, b -> write(b, Format.JPG, "fz$i") }
        val seedFile = File(dir, "fuzz-seed.pdf")
        pdf.imagesToPdf(photos, seedFile, PageSize.A4)
        val seed = seedFile.readBytes()
        val random = Random(42)
        var opened = 0
        var rejected = 0
        var crashed = 0
        var timedOut = 0
        repeat(count) { i ->
            step = "PDF fuzz pass: ${i + 1} of $count"
            val bytes = seed.copyOf()
            repeat(random.nextInt(1, 40)) { bytes[random.nextInt(5, bytes.size)] = random.nextInt(256).toByte() }
            val data = if (random.nextInt(4) == 0) bytes.copyOf(random.nextInt(5, bytes.size)) else bytes
            val f = File(dir, "fuzz.pdf").apply { writeBytes(data) }
            try {
                pdf.pageCount(f)
                pdf.renderPixels(f, 0, 36).getParcelable("mem", SharedMemory::class.java)?.close()
                pdf.compress(f, File(dir, "fuzz-out.pdf"), 70, 72, 2)
                opened++
            } catch (e: ParserFailure) {
                val m = e.message.orEmpty()
                when {
                    "crashed" in m -> crashed++
                    "too long" in m -> timedOut++
                    else -> rejected++
                }
            }
            tick()
        }
        report(true, "Fuzz PDF × $count: app survived. $opened went through all three readers, $rejected rejected cleanly, $crashed crashed a reader (recovered), $timedOut timed out")
    }

    /**
     * Phase 2 gate and S14: protected PDFs through the engine, then through real jobs the way the app runs
     * them (asked, wrong password, right password), then a scan of all app storage for the password.
     * The jobs write to the output folder; their outputs are deleted afterwards.
     */
    private suspend fun passwords(pdf: PdfEngineClient) {
        val secret = "ffT-" + java.util.UUID.randomUUID().toString().take(12)
        val plain = makePdf(3, "plain") { Color.WHITE }
        val locked = File(dir, "locked.pdf")
        pdf.assemble(listOf(plain), List(3) { PageRef(0, it) }, locked, newPassword = secret)
        val asked = failureCode { pdf.pageCount(locked) }
        val wrong = failureCode { pdf.pageCount(locked, "not-it") }
        report(
            asked == PdfEngineClient.PASSWORD_NEEDED && wrong == PdfEngineClient.WRONG_PASSWORD && pdf.pageCount(locked, secret) == 3,
            "Protected PDF: no password → asks ($asked), wrong → \"wrong\" ($wrong), right → opens",
        )
        tick()
        val page = pdf.renderBitmap(locked, 0, 200, secret)
        report(page.width == 200, "Protected PDF renders with its password (Android's PDF renderer)")
        tick()
        val kept = File(dir, "kept.pdf")
        val removed = File(dir, "removed.pdf")
        pdf.compress(locked, kept, 70, 72, 3, secret)
        pdf.compress(locked, removed, 70, 72, 3, secret, removePassword = true)
        report(
            failureCode { pdf.pageCount(kept) } == PdfEngineClient.PASSWORD_NEEDED && pdf.pageCount(removed) == 3,
            "Compress keeps the password; \"Save without a password\" removes it",
        )
        tick()

        val files = listOf(IncomingFile(Uri.fromFile(locked), "locked.pdf", Format.PDF), IncomingFile(Uri.fromFile(plain), "plain.pdf", Format.PDF))
        suspend fun mergeJob(removePassword: Boolean): Pair<File?, String> {
            val id = JobQueue.enqueue(context, files, Format.PDF, Preset())
            var job = waitForJob(id)
            val askedFirst = job.inputs[0].needsPassword
            JobQueue.retry(context, id, mapOf(0 to "not-it"))
            job = waitForJob(id)
            val wrongAgain = job.inputs[0].needsPassword && job.inputs[0].error != PdfEngineClient.PASSWORD_NEEDED_MESSAGE
            JobQueue.retry(context, id, mapOf(0 to secret), removePassword)
            job = waitForJob(id)
            val out = job.outputs.firstOrNull()?.let { output ->
                File(dir, "job-out.pdf").also { f ->
                    context.contentResolver.openInputStream(Uri.parse(output.uri))!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(output.uri)) }
                }
            }
            JobQueue.dismiss(context, id)
            return out to "asked $askedFirst, wrong password caught $wrongAgain, saved ${out != null}"
        }
        val (merged, steps) = mergeJob(removePassword = false)
        report(
            merged != null && failureCode { pdf.pageCount(merged) } == PdfEngineClient.PASSWORD_NEEDED && pdf.pageCount(merged, secret) == 6,
            "Gate: merging a protected PDF (as a real job) gives a protected output: $steps",
        )
        tick()
        val (open, steps2) = mergeJob(removePassword = true)
        report(open != null && pdf.pageCount(open) == 6, "Merge with \"Save without a password\" gives an open PDF: $steps2")
        tick()

        // S14: the password must not be anywhere in app storage (job files, WorkManager database, settings, cache).
        val needles = listOf(secret.toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_16LE))
        val found = context.dataDir.walkTopDown()
            .filter { it.isFile && it.length() < 50L * 1024 * 1024 }
            .filter { f -> runCatching { f.readBytes() }.getOrNull()?.let { bytes -> needles.any { indexOf(bytes, it) >= 0 } } == true }
            .map { it.relativeTo(context.dataDir).path }
            .toList()
        report(found.isEmpty(), "S14: password found in app storage: ${if (found.isEmpty()) "nowhere" else found.joinToString()}")
        tick()
    }

    private suspend fun failureCode(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: ParserFailure) {
        e.code
    }

    /** Waits until every input of job [id] is done or failed; 2 minutes at most. */
    private suspend fun waitForJob(id: String): JobRecord {
        val store = JobStore(context)
        repeat(400) {
            kotlinx.coroutines.delay(300)
            val job = store.load(id)
            if (job != null && job.inputs.all { it.done || it.error != null }) return job
        }
        error("job did not finish in 2 minutes")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** A test PDF made by Android's own PDF writer, so FileForge's readers see a file it did not write. */
    private fun makePdf(pages: Int, name: String, background: (Int) -> Int): File {
        val doc = PdfDocument()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = Color.BLACK }
        val photo = pattern(400, 260)
        repeat(pages) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawColor(background(i))
            page.canvas.drawText("FileForge test page ${i + 1}", 60f, 80f, text)
            page.canvas.drawBitmap(photo, 60f, 120f, null)
            doc.finishPage(page)
        }
        val f = File(dir, "$name.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        photo.recycle()
        return f
    }

    /** An A4 page scanned at 300 dpi: off-white paper grain and lines of dark text. */
    private fun scanPage(n: Int): Bitmap {
        val w = 2480
        val h = 3508
        val random = Random(n)
        val grain = IntArray(w * h) { val g = 238 + random.nextInt(12); Color.rgb(g, g, g - 4) }
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(grain, 0, w, 0, 0, w, h) }
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30); textSize = 48f }
        for (line in 0 until 60) c.drawText("Page $n, line ${line + 1}: the quick brown fox jumps over the lazy dog 0123456789", 200f, 300f + line * 50f, p)
        return b
    }

    private fun centre(b: Bitmap) = b.getPixel(b.width / 2, b.height / 2).also { b.recycle() }

    private fun close(a: Int, b: Int) =
        kotlin.math.abs(Color.red(a) - Color.red(b)) < 40 && kotlin.math.abs(Color.green(a) - Color.green(b)) < 40 && kotlin.math.abs(Color.blue(a) - Color.blue(b)) < 40

    /** Mean per-channel difference between two renders of the same page, 0–255. */
    private fun meanDifference(a: Bitmap, b: Bitmap): Long {
        if (a.width != b.width || a.height != b.height) return 255
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var sum = 0L
        for (i in pa.indices) {
            sum += kotlin.math.abs(Color.red(pa[i]) - Color.red(pb[i])) + kotlin.math.abs(Color.green(pa[i]) - Color.green(pb[i])) + kotlin.math.abs(Color.blue(pa[i]) - Color.blue(pb[i]))
        }
        a.recycle()
        b.recycle()
        return sum / (pa.size * 3L)
    }

    private fun securityChecks() {
        // The right-to-left override is built at run time so the source stays free of bidi characters.
        val hostile = listOf("../../etc/passwd", "a/b\\c", "..", "evil" + Char(0x202E) + "gpj.exe", "x\u0000y")
        report(
            hostile.all { FileNames.sanitize(it).let { s -> '/' !in s && '\\' !in s && ".." !in s && s.none(Char::isISOControl) } },
            "S7: hostile names sanitised",
        )
        tick()
        val share = Outputs.shareIntent(Uri.parse("content://test/doc"), "image/jpeg")
        val inner = share.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        val readOnly = { i: Intent -> i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 && i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 }
        report(readOnly(share) && readOnly(inner), "S8: share grants read-only access to one file")
        tick()
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

    private companion object {
        const val PHOTOS = 100
        const val SPEED_RUNS = 3
        /** Checks in the PDF sections, not counting the PDF fuzz pass. */
        const val PDF_TICKS = 4 + 3 + 4 + 2 + 2 + 6
    }

    private fun describe(o: Outcome) = when (o) {
        is Outcome.Success -> "ok"
        is Outcome.Failure -> "\"${o.message}\""
    }
}
