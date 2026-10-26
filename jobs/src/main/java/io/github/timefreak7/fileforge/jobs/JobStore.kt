package io.github.timefreak7.fileforge.jobs

import android.content.Context
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.Preset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One input of a job. Its bytes live at `jobs/<id>/in/<index>` until the job has converted it. */
data class JobInput(
    /** Display name only (S7): never used as a path. */
    val name: String,
    /** Detected from the file's bytes; null when it is not a supported image. */
    val format: Format?,
    val outputUri: String? = null,
    val outputName: String? = null,
    val error: String? = null,
) {
    val done: Boolean get() = outputUri != null
}

data class JobRecord(
    val id: String,
    val createdAt: Long,
    val target: Format,
    val preset: Preset,
    val inputs: List<JobInput>,
) {
    val failed: List<JobInput> get() = inputs.filter { it.error != null }
}

/** Job records as small JSON files in app storage. Excluded from backup with the rest of the app (S9). */
class JobStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "jobs")

    fun dir(id: String) = File(root, id)
    fun inputDir(id: String) = File(dir(id), "in")
    fun inputFile(id: String, index: Int) = File(inputDir(id), index.toString())

    @Synchronized
    fun save(job: JobRecord) {
        val dir = dir(job.id).apply { mkdirs() }
        val tmp = File(dir, "job.json.tmp")
        tmp.writeText(toJson(job).toString())
        tmp.renameTo(File(dir, "job.json"))
    }

    @Synchronized
    fun load(id: String): JobRecord? = runCatching { fromJson(JSONObject(File(dir(id), "job.json").readText())) }.getOrNull()

    /** Newest first. */
    fun all(): List<JobRecord> = root.listFiles().orEmpty().mapNotNull { load(it.name) }.sortedByDescending { it.createdAt }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    /**
     * Runs at every app start. Finished inputs are already gone; inputs kept for Retry go after
     * 7 days, and job history after 30.
     */
    fun prune(now: Long = System.currentTimeMillis(), isRunning: (String) -> Boolean) {
        for (job in all()) {
            if (isRunning(job.id)) continue
            val age = now - job.createdAt
            if (age > 30 * DAY) delete(job.id) else if (age > 7 * DAY) inputDir(job.id).deleteRecursively()
        }
    }

    private fun toJson(job: JobRecord) = JSONObject().apply {
        put("id", job.id)
        put("createdAt", job.createdAt)
        put("target", job.target.name)
        put("quality", job.preset.quality)
        put("maxSide", job.preset.maxSide)
        put("keepMetadata", job.preset.keepMetadata)
        job.preset.outputName?.let { put("outputName", it) }
        put("inputs", JSONArray(job.inputs.map { input ->
            JSONObject().apply {
                put("name", input.name)
                input.format?.let { put("format", it.name) }
                input.outputUri?.let { put("outputUri", it) }
                input.outputName?.let { put("outputNameDone", it) }
                input.error?.let { put("error", it) }
            }
        }))
    }

    private fun fromJson(o: JSONObject): JobRecord {
        val inputs = o.getJSONArray("inputs")
        return JobRecord(
            id = o.getString("id"),
            createdAt = o.getLong("createdAt"),
            target = Format.valueOf(o.getString("target")),
            preset = Preset(
                quality = o.getInt("quality"),
                maxSide = o.getInt("maxSide"),
                keepMetadata = o.getBoolean("keepMetadata"),
                outputName = o.optString("outputName").ifEmpty { null },
            ),
            inputs = List(inputs.length()) { i ->
                val x = inputs.getJSONObject(i)
                JobInput(
                    name = x.getString("name"),
                    format = x.optString("format").ifEmpty { null }?.let(Format::valueOf),
                    outputUri = x.optString("outputUri").ifEmpty { null },
                    outputName = x.optString("outputNameDone").ifEmpty { null },
                    error = x.optString("error").ifEmpty { null },
                )
            },
        )
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
