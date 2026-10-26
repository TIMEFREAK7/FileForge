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
import io.github.timefreak7.fileforge.core.FileNames
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.Limits
import io.github.timefreak7.fileforge.core.Preset
import io.github.timefreak7.fileforge.core.Sniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        withContext(Dispatchers.IO) {
            require(files.size <= Limits.MAX_FILES_PER_JOB) { "too many files" }
            val store = JobStore(context)
            val id = UUID.randomUUID().toString()
            store.inputDir(id).mkdirs()
            val inputs = files.mapIndexed { i, f -> copyIn(context, f, store.inputFile(id, i)) }
            store.save(JobRecord(id, System.currentTimeMillis(), target, preset, inputs))
            start(context, id)
            id
        }

    /** Re-runs the inputs of [id] that failed. Inputs already converted are skipped. */
    fun retry(context: Context, id: String) {
        val store = JobStore(context)
        val job = store.load(id) ?: return
        store.save(job.copy(inputs = job.inputs.map { if (it.done) it else it.copy(error = null) }))
        start(context, id)
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(id)
    }

    /** Removes a finished job and the inputs it kept for Retry. Converted files stay in the output folder. */
    fun dismiss(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(id)
        JobStore(context).delete(id)
    }

    fun observe(context: Context): Flow<List<JobView>> {
        val store = JobStore(context)
        return WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            // Retry replaces the work, so one job can have several; the unfinished one wins.
            val byId = infos
                .mapNotNull { info -> info.tags.firstOrNull { it != TAG && !it.contains('.') }?.let { it to info } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, list) -> list.firstOrNull { !it.state.isFinished } ?: list.last() }
            store.all().map { record ->
                val info = byId[record.id]
                val progress = info?.progress
                JobView(
                    record,
                    info?.state,
                    progress?.getInt(ConvertWorker.DONE, 0)?.takeIf { info.state == WorkInfo.State.RUNNING }
                        ?: record.inputs.count { it.done || it.error != null },
                    record.inputs.size,
                )
            }
        }
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

    private fun copyIn(context: Context, file: IncomingFile, dest: File): JobInput {
        val name = file.name.take(200)
        return try {
            val tooBig = context.contentResolver.openInputStream(file.uri)!!.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > Limits.MAX_IMAGE_BYTES) return@use true
                        out.write(buffer, 0, n)
                    }
                    false
                }
            }
            if (tooBig) {
                dest.delete()
                return JobInput(name, null, error = "This file is larger than ${Limits.MAX_IMAGE_BYTES / (1024 * 1024)} MB.")
            }
            val header = dest.inputStream().use { it.readNBytes(Sniffer.HEADER_BYTES) }
            val format = Sniffer.sniff(header)
            if (format == null) {
                dest.delete()
                JobInput(name, null, error = if (header.isEmpty()) "This file is empty." else "This is not a JPG, PNG, WebP or HEIC image.")
            } else {
                JobInput(name, format)
            }
        } catch (e: Exception) {
            dest.delete()
            JobInput(name, null, error = "This file could not be read. If it was shared from another app, share it again.")
        }
    }

    /** Output name for one input of [job]: the chosen name applies only to single-file jobs. */
    internal fun outputName(job: JobRecord, input: JobInput) =
        FileNames.outputName(input.name, job.target, job.preset.outputName.takeIf { job.inputs.size == 1 })
}
