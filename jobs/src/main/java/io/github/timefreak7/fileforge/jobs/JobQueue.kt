package io.github.timefreak7.fileforge.jobs

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.Limits
import io.github.timefreak7.fileforge.core.Preset
import io.github.timefreak7.fileforge.core.Sniffer
import io.github.timefreak7.fileforge.pdf.PageRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A job as the Jobs and Home screens show it. */
data class JobView(val record: JobRecord, val state: WorkInfo.State?, val done: Int, val total: Int) {
    val running: Boolean get() = state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED
}

/** A picked or shared file, as known before it is copied. */
data class IncomingFile(val uri: Uri, val name: String, val format: Format?)

object JobQueue {
    private const val TAG = "job"

    /** Bumped when job records change without any WorkManager state change (Remove, Retry). */
    private val recordsChanged = MutableStateFlow(0)

    /** Display name and claimed type of a content URI, for the donut. The real type is sniffed on copy. */
    fun describe(context: Context, uri: Uri): IncomingFile {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
        val format = Format.fromMime(context.contentResolver.getType(uri)) ?: Format.fromExtension(name)
        return IncomingFile(uri, name, format)
    }

    /**
     * Copies the inputs into app storage straight away (a share grant can expire before the job
     * runs), then queues the job. Inputs over the size limit are recorded as failed, not copied.
     */
    suspend fun enqueue(context: Context, files: List<IncomingFile>, target: Format, preset: Preset): String =
        add(context, files, target, preset, opFor(files.map { it.format }, target))

    /** The organiser's result: [pages] into one PDF, or one PDF per group of [splits]. Inputs are PDFs. */
    suspend fun enqueuePages(
        context: Context,
        files: List<IncomingFile>,
        preset: Preset,
        pages: List<PageRef> = emptyList(),
        splits: List<List<PageRef>> = emptyList(),
    ): String = add(context, files, Format.PDF, preset, if (splits.isEmpty()) JobOp.ASSEMBLE_PDF else JobOp.SPLIT_PDF, pages, splits)

    private suspend fun add(
        context: Context,
        files: List<IncomingFile>,
        target: Format,
        preset: Preset,
        op: JobOp,
        pages: List<PageRef> = emptyList(),
        splits: List<List<PageRef>> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        require(files.size <= Limits.MAX_FILES_PER_JOB) { "too many files" }
        val store = JobStore(context)
        val id = UUID.randomUUID().toString()
        store.inputDir(id).mkdirs()
        val pdf = op != JobOp.CONVERT && (op != JobOp.COMBINE_PDF || files.all { it.format == Format.PDF })
        val inputs = files.mapIndexed { i, f -> copyIn(context, f, store.inputFile(id, i), pdf) }
        store.save(JobRecord(id, System.currentTimeMillis(), target, preset, inputs, op, pages = pages, splits = splits))
        start(context, id)
        id
    }

    /** Images: convert, or one PDF of all of them. PDFs: pages to images, compress one, or merge several. */
    internal fun opFor(formats: List<Format?>, target: Format): JobOp {
        val pdfs = formats.any { it == Format.PDF }
        return when {
            !pdfs -> if (target == Format.PDF) JobOp.COMBINE_PDF else JobOp.CONVERT
            target != Format.PDF -> JobOp.PDF_TO_IMAGES
            formats.size == 1 -> JobOp.COMPRESS_PDF
            else -> JobOp.COMBINE_PDF
        }
    }

    /** Re-runs the inputs of [id] that failed. Inputs already converted are skipped. */
    fun retry(context: Context, id: String) {
        val store = JobStore(context)
        val job = store.load(id) ?: return
        store.save(job.copy(inputs = job.inputs.map { if (it.done) it else it.copy(error = null, note = null) }))
        start(context, id)
        recordsChanged.update { it + 1 }
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(id)
    }

    /** Removes a finished job and the inputs it kept for Retry. Converted files stay in the output folder. */
    fun dismiss(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(id)
        JobStore(context).delete(id)
        recordsChanged.update { it + 1 }
    }

    fun observe(context: Context): Flow<List<JobView>> {
        val store = JobStore(context)
        // Removing a finished job changes no WorkInfo, so record changes are a second trigger.
        return combine(WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG), recordsChanged) { infos, _ ->
            // Retry replaces the work, so one job can have several; the unfinished one wins.
            val byId = infos
                .mapNotNull { info -> info.tags.firstOrNull { it != TAG && !it.contains('.') }?.let { it to info } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, list) -> list.firstOrNull { !it.state.isFinished } ?: list.last() }
            store.all().map { record ->
                val info = byId[record.id]
                // While running, the worker reports in its own units (pages for PDF jobs).
                val progress = info?.progress?.takeIf { info.state == WorkInfo.State.RUNNING && it.getInt(ConvertWorker.TOTAL, 0) > 0 }
                JobView(
                    record,
                    info?.state,
                    progress?.getInt(ConvertWorker.DONE, 0) ?: record.inputs.count { it.done || it.error != null },
                    progress?.getInt(ConvertWorker.TOTAL, 0) ?: record.inputs.size,
                )
            }
        }.flowOn(Dispatchers.IO) // reads job files; keep it off the UI thread
    }

    /** Clears temporary outputs and old job data; runs at every app start. */
    fun cleanUp(context: Context) {
        val running = WorkManager.getInstance(context).getWorkInfosByTag(TAG).get()
            .filter { !it.state.isFinished }
            .flatMap { it.tags }
            .toSet()
        // A job WorkManager resumes after a restart may already be writing here; leave its folder.
        File(context.cacheDir, "jobs").listFiles().orEmpty().filter { it.name !in running }.forEach { it.deleteRecursively() }
        JobStore(context).prune { it in running }
    }

    private fun start(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<ConvertWorker>()
            .setInputData(workDataOf(ConvertWorker.JOB_ID to id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .addTag(id)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
    }

    /** [pdf]: whether this job expects PDFs; anything else is recorded as failed. */
    private fun copyIn(context: Context, file: IncomingFile, dest: File, pdf: Boolean): JobInput {
        val name = file.name.take(200)
        val limit = if (pdf) Limits.MAX_PDF_BYTES else Limits.MAX_IMAGE_BYTES
        return try {
            val tooBig = context.contentResolver.openInputStream(file.uri)!!.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limit) return@use true
                        out.write(buffer, 0, n)
                    }
                    false
                }
            }
            if (tooBig) {
                dest.delete()
                return JobInput(name, null, error = "This file is larger than ${limit / (1024 * 1024)} MB.")
            }
            val header = dest.inputStream().use { it.readNBytes(Sniffer.HEADER_BYTES) }
            val format = Sniffer.sniff(header)
            if (format == null || (format == Format.PDF) != pdf) {
                dest.delete()
                val error = when {
                    header.isEmpty() -> "This file is empty."
                    pdf -> "This is not a PDF."
                    else -> "This is not a JPG, PNG, WebP or HEIC image."
                }
                JobInput(name, null, error = error)
            } else {
                JobInput(name, format)
            }
        } catch (e: Exception) {
            dest.delete()
            JobInput(name, null, error = "This file could not be read. If it was shared from another app, share it again.")
        }
    }
}
