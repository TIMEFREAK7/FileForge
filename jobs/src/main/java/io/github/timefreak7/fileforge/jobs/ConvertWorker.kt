package io.github.timefreak7.fileforge.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.timefreak7.fileforge.image.ImageEngineClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Converts every not-yet-done input of one job and writes the results to the output folder. */
class ConvertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val store = JobStore(context)
    private val notificationId = params.id.hashCode()

    override suspend fun getForegroundInfo() = foregroundInfo(0, 0)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(JOB_ID) ?: return@withContext Result.failure()
        var job = store.load(id) ?: return@withContext Result.failure()
        val total = job.inputs.size
        // Not allowed when the app is in the background (e.g. swiped away before the job started):
        // the job then runs without a notification, within the normal background time limit.
        runCatching { setForeground(foregroundInfo(job.inputs.count { it.done }, total)) }

        val tree = AppSettings(applicationContext).outputTree
        val workDir = File(applicationContext.cacheDir, "jobs/$id").apply { mkdirs() }
        ImageEngineClient(applicationContext).use { engine ->
            job.inputs.forEachIndexed { index, input ->
                if (input.done || input.format == null) return@forEachIndexed
                val result = if (tree == null) {
                    input.copy(error = "No output folder is set. Choose one in Settings, then tap Retry.")
                } else {
                    convertOne(engine, job, index, input, tree, workDir)
                }
                job = job.copy(inputs = job.inputs.toMutableList().also { it[index] = result })
                store.save(job)
                if (result.done) store.inputFile(id, index).delete()
                val finished = job.inputs.count { it.done || it.error != null }
                setProgress(workDataOf(DONE to finished, TOTAL to total))
                runCatching { setForeground(foregroundInfo(finished, total)) }
            }
        }
        workDir.deleteRecursively()
        if (job.inputs.all { it.done }) store.inputDir(id).deleteRecursively()
        if (job.inputs.any { it.done }) Result.success() else Result.failure()
    }

    private suspend fun convertOne(
        engine: ImageEngineClient,
        job: JobRecord,
        index: Int,
        input: JobInput,
        tree: Uri,
        workDir: File,
    ): JobInput {
        val source = store.inputFile(job.id, index)
        if (!source.exists()) return input.copy(error = "The original copy is gone; pick or share the file again.")
        val temp = File(workDir, "$index.${job.target.extension}")
        var document: Uri? = null
        try {
            val outcome = engine.convert(source, input.format!!, temp, job.target, job.preset)
            if (outcome is ImageEngineClient.Outcome.Failure) return input.copy(error = outcome.message)

            val name = JobQueue.outputName(job, input)
            val resolver = applicationContext.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            document = DocumentsContract.createDocument(resolver, parent, job.target.mime, name)
                ?: return input.copy(error = "Could not save to the output folder. Check it still exists in Settings.")
            resolver.openOutputStream(document)!!.use { out -> temp.inputStream().use { it.copyTo(out) } }
            val saved = document
            document = null
            return input.copy(outputUri = saved.toString(), outputName = name, error = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            return input.copy(error = "FileForge no longer has access to the output folder. Choose it again in Settings.")
        } catch (e: Exception) {
            return input.copy(error = "Could not save to the output folder (${e.javaClass.simpleName}).")
        } finally {
            temp.delete()
            // A cancelled or failed save leaves no partial file behind.
            document?.let { partial ->
                withContext(NonCancellable) {
                    runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, partial) }
                }
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
