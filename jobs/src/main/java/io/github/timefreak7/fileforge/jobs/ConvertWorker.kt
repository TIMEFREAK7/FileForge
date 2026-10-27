package io.github.timefreak7.fileforge.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.SharedMemory
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.timefreak7.fileforge.core.FileNames
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.image.ImageEngineClient
import io.github.timefreak7.fileforge.image.ParserFailure
import io.github.timefreak7.fileforge.pdf.PageRef
import io.github.timefreak7.fileforge.pdf.PdfEngineClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Runs every not-yet-done part of one job and writes the results to the output folder. */
class ConvertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val store = JobStore(context)
    private val notificationId = params.id.hashCode()
    private lateinit var job: JobRecord
    private lateinit var tree: Uri
    private lateinit var workDir: File

    /** A step failed; the message is shown to the user as is. */
    private class StepFailure(message: String) : Exception(message)

    override suspend fun getForegroundInfo() = foregroundInfo(0, 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(JOB_ID) ?: return@withContext Result.failure()
        job = store.load(id) ?: return@withContext Result.failure()
        // Not allowed when the app is in the background (e.g. swiped away before the job started):
        // the job then runs without a notification, within the normal background time limit.
        runCatching { setForeground(foregroundInfo(job.inputs.count { it.done }, job.inputs.size)) }

        val tree = AppSettings(applicationContext).outputTree
        if (tree == null) {
            failPending("No output folder is set. Choose one in Settings, then tap Retry.")
            return@withContext Result.failure()
        }
        this@ConvertWorker.tree = tree
        workDir = File(applicationContext.cacheDir, "jobs/$id").apply { mkdirs() }
        ImageEngineClient(applicationContext).use { images ->
            PdfEngineClient(applicationContext).use { pdf ->
                when (job.op) {
                    JobOp.CONVERT -> eachInput { i, input -> convertImage(images, i, input) }
                    JobOp.COMPRESS_PDF -> eachInput { i, input -> compress(pdf, i, input) }
                    JobOp.PDF_TO_IMAGES -> eachInput { i, input -> pdfToImages(images, pdf, i, input) }
                    JobOp.COMBINE_PDF -> whole { combine(images, pdf) }
                    JobOp.ASSEMBLE_PDF -> whole { assemble(pdf, job.pages, suffix = if (chosen == null) "_edited" else "") }
                    JobOp.SPLIT_PDF -> whole { split(pdf) }
                }
            }
        }
        workDir.deleteRecursively()
        if (job.inputs.all { it.done }) store.inputDir(id).deleteRecursively()
        if (job.inputs.any { it.done }) Result.success() else Result.failure()
    }

    private fun update(change: (JobRecord) -> JobRecord) {
        job = change(job)
        store.save(job)
    }

    private fun updateInput(index: Int, change: (JobInput) -> JobInput) =
        update { j -> j.copy(inputs = j.inputs.toMutableList().also { it[index] = change(it[index]) }) }

    private fun failPending(message: String) =
        update { j -> j.copy(inputs = j.inputs.map { if (it.done || it.error != null) it else it.copy(error = message) }) }

    private suspend fun progress(done: Int, total: Int) {
        setProgress(workDataOf(DONE to done, TOTAL to total))
        runCatching { setForeground(foregroundInfo(done, total)) }
    }

    /** Runs [block] and turns any failure into the plain-language message to show, or null on success. */
    private suspend fun attempt(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: ParserFailure) {
        e.message ?: "The file could not be processed."
    } catch (e: StepFailure) {
        e.message
    } catch (e: SecurityException) {
        "FileForge no longer has access to the output folder. Choose it again in Settings."
    } catch (e: Exception) {
        "Could not save to the output folder (${e.javaClass.simpleName})."
    }

    /** One-to-one and one-to-many jobs: each input succeeds or fails on its own. */
    private suspend fun eachInput(step: suspend (Int, JobInput) -> Unit) {
        job.inputs.forEachIndexed { index, input ->
            if (input.done || input.format == null) return@forEachIndexed
            val error = attempt { step(index, input) }
            updateInput(index) { if (error == null) it.copy(done = true) else it.copy(error = error) }
            if (error == null) store.inputFile(job.id, index).delete()
            progress(job.inputs.count { it.done || it.error != null }, job.inputs.size)
        }
    }

    /** Many-to-one jobs: one bad input fails the whole job rather than leaving a page out. */
    private suspend fun whole(step: suspend () -> Unit) {
        if (job.inputs.any { it.error != null }) {
            failPending("Another file in this job could not be used, so nothing was saved.")
            return
        }
        val error = attempt { step() }
        if (error == null) update { j -> j.copy(inputs = j.inputs.map { it.copy(done = true) }) } else failPending(error)
    }

    private fun source(index: Int): File = store.inputFile(job.id, index).takeIf { it.exists() }
        ?: throw StepFailure("The original copy is gone; pick or share the file again.")

    /** The user's chosen name, which applies when the job saves one file, or one set of numbered files from one input. */
    private val chosen: String?
        get() = job.preset.outputName.takeIf { job.inputs.size == 1 || job.singleOutput || job.op == JobOp.SPLIT_PDF }

    private fun name(input: JobInput, format: Format, suffix: String = "") = FileNames.outputName(input.name, format, chosen, suffix)

    private suspend fun convertImage(images: ImageEngineClient, index: Int, input: JobInput) {
        val temp = File(workDir, "$index.${job.target.extension}")
        val outcome = images.convert(source(index), input.format!!, temp, job.target, job.preset)
        if (outcome is ImageEngineClient.Outcome.Failure) throw StepFailure(outcome.message)
        save(temp, name(input, job.target), job.target)
    }

    private suspend fun compress(pdf: PdfEngineClient, index: Int, input: JobInput) {
        val src = source(index)
        val temp = File(workDir, "$index.pdf")
        val replaced = pdf.compress(src, temp, job.preset.quality, job.preset.dpi, pdf.pageCount(src))
        if (replaced == 0 || temp.length() >= src.length()) {
            temp.delete()
            updateInput(index) { it.copy(note = "Already as small as it gets, so no copy was saved.") }
            return
        }
        save(temp, name(input, Format.PDF, if (chosen == null) "_small" else ""), Format.PDF)
    }

    private suspend fun pdfToImages(images: ImageEngineClient, pdf: PdfEngineClient, index: Int, input: JobInput) {
        val src = source(index)
        val count = pdf.pageCount(src)
        val temp = File(workDir, "$index.${job.target.extension}")
        for (page in job.inputs[index].pagesDone until count) {
            val pixels = pdf.renderPixels(src, page, job.preset.dpi)
            try {
                images.encode(pixels, temp, job.target, job.preset.quality)
            } finally {
                pixels.getParcelable("mem", SharedMemory::class.java)?.close()
            }
            save(temp, name(input, job.target, "_p%03d".format(page + 1)), job.target)
            updateInput(index) { it.copy(pagesDone = page + 1) }
            progress(page + 1, count)
        }
    }

    private suspend fun combine(images: ImageEngineClient, pdf: PdfEngineClient) {
        if (job.inputs.all { it.format == Format.PDF }) {
            val pages = job.inputs.indices.flatMap { i -> List(pdf.pageCount(source(i))) { PageRef(i, it) } }
            assemble(pdf, pages, suffix = if (chosen == null) "_merged" else "")
            return
        }
        // FileForge re-encodes every image as JPEG first, so ":media" only ever reads its own files.
        val jpegs = job.inputs.mapIndexed { i, input ->
            val jpeg = File(workDir, "$i.jpg")
            val outcome = images.convert(source(i), input.format!!, jpeg, Format.JPG, job.preset.copy(keepMetadata = false))
            if (outcome is ImageEngineClient.Outcome.Failure) throw StepFailure("${input.name}: ${outcome.message}")
            progress(i + 1, job.inputs.size + 1)
            jpeg
        }
        val temp = File(workDir, "out.pdf")
        try {
            pdf.imagesToPdf(jpegs, temp, job.preset.pageSize)
        } finally {
            jpegs.forEach { it.delete() }
        }
        save(temp, name(job.inputs[0], Format.PDF), Format.PDF)
    }

    private suspend fun assemble(pdf: PdfEngineClient, pages: List<PageRef>, suffix: String) {
        val temp = File(workDir, "out.pdf")
        pdf.assemble(job.inputs.indices.map { source(it) }, pages, temp)
        save(temp, name(job.inputs[0], Format.PDF, suffix), Format.PDF)
    }

    /** One file per group; carries on from the last saved part after Retry or a restart. */
    private suspend fun split(pdf: PdfEngineClient) {
        val sources = job.inputs.indices.map { source(it) }
        for (part in job.outputs.size until job.splits.size) {
            val temp = File(workDir, "part.pdf")
            pdf.assemble(sources, job.splits[part], temp)
            save(temp, name(job.inputs[0], Format.PDF, "_part${part + 1}"), Format.PDF)
            progress(part + 1, job.splits.size)
        }
    }

    /** Copies [temp] into the output folder as [name] and records it. A failed save leaves no partial file. */
    private suspend fun save(temp: File, name: String, format: Format) {
        val resolver = applicationContext.contentResolver
        var document: Uri? = null
        try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            document = DocumentsContract.createDocument(resolver, parent, format.mime, name)
                ?: throw StepFailure("Could not save to the output folder. Check it still exists in Settings.")
            resolver.openOutputStream(document)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
            val saved = JobOutput(document.toString(), name, format)
            document = null
            update { it.copy(outputs = it.outputs + saved) }
        } finally {
            temp.delete()
            document?.let { partial ->
                withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(resolver, partial) } }
            }
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Conversions", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_convert)
            .setContentTitle("Converting files")
            .setContentText(if (total > 0) "$done of $total done" else "Starting…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
    }

    companion object {
        const val JOB_ID = "jobId"
        const val DONE = "done"
        const val TOTAL = "total"
        private const val CHANNEL = "jobs"
    }
}
