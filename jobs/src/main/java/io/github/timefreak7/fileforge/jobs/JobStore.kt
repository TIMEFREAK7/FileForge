package io.github.timefreak7.fileforge.jobs

import android.content.Context
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.PageSize
import io.github.timefreak7.fileforge.core.Preset
import io.github.timefreak7.fileforge.pdf.PageRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What a job does with its inputs. */
enum class JobOp {
    /** Image to image, one output per input. */
    CONVERT,
    /** Each PDF to one image per page. */
    PDF_TO_IMAGES,
    /** All inputs into one PDF: images become pages, PDFs are merged. */
    COMBINE_PDF,
    /** Each PDF to a smaller PDF. */
    COMPRESS_PDF,
    /** The organiser's pages, in order, into one PDF. */
    ASSEMBLE_PDF,
    /** The organiser's page groups, one PDF each. */
    SPLIT_PDF,
}

/** One input of a job. Its bytes live at `jobs/<id>/in/<index>` until the job is finished with it. */
data class JobInput(
    /** Display name only (S7): never used as a path. */
    val name: String,
    /** Detected from the file's bytes; null when it is not a supported file. */
    val format: Format?,
    val done: Boolean = false,
    /** PDF to images: pages already saved, so Retry carries on instead of saving them twice. */
    val pagesDone: Int = 0,
    val error: String? = null,
    /** Something worth knowing that is not a failure, e.g. "already as small as it gets". */
    val note: String? = null,
    /** The last failure was a missing or wrong PDF password: the UI asks for it. The password itself is never stored. */
    val needsPassword: Boolean = false,
)

/** A file the job saved in the output folder. */
data class JobOutput(val uri: String, val name: String, val format: Format)

data class JobRecord(
    val id: String,
    val createdAt: Long,
    val target: Format,
    val preset: Preset,
    val inputs: List<JobInput>,
    val op: JobOp = JobOp.CONVERT,
    val outputs: List<JobOutput> = emptyList(),
    val pages: List<PageRef> = emptyList(),
    val splits: List<List<PageRef>> = emptyList(),
    /** The user chose to save protected PDFs without their password (S14). */
    val removePassword: Boolean = false,
) {
    val failed: List<JobInput> get() = inputs.filter { it.error != null }

    /** Many-to-one jobs succeed or fail as a whole. */
    val singleOutput: Boolean get() = op == JobOp.COMBINE_PDF || op == JobOp.ASSEMBLE_PDF
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

    private fun pagesJson(pages: List<PageRef>) = JSONArray(pages.flatMap { listOf(it.input, it.page, it.rotation) })

    private fun pagesFrom(a: JSONArray) = List(a.length() / 3) { PageRef(a.getInt(it * 3), a.getInt(it * 3 + 1), a.getInt(it * 3 + 2)) }

    private fun toJson(job: JobRecord) = JSONObject().apply {
        put("id", job.id)
        put("createdAt", job.createdAt)
        put("op", job.op.name)
        put("target", job.target.name)
        put("quality", job.preset.quality)
        put("maxSide", job.preset.maxSide)
        put("keepMetadata", job.preset.keepMetadata)
        put("dpi", job.preset.dpi)
        put("pageSize", job.preset.pageSize.name)
        job.preset.outputName?.let { put("outputName", it) }
        put("inputs", JSONArray(job.inputs.map { input ->
            JSONObject().apply {
                put("name", input.name)
                input.format?.let { put("format", it.name) }
                put("done", input.done)
                put("pagesDone", input.pagesDone)
                input.error?.let { put("error", it) }
                input.note?.let { put("note", it) }
                put("needsPassword", input.needsPassword)
            }
        }))
        put("outputs", JSONArray(job.outputs.map { JSONObject().put("uri", it.uri).put("name", it.name).put("format", it.format.name) }))
        put("pages", pagesJson(job.pages))
        put("splits", JSONArray(job.splits.map { pagesJson(it) }))
        put("removePassword", job.removePassword)
    }

    private fun fromJson(o: JSONObject): JobRecord {
        val target = Format.valueOf(o.getString("target"))
        val inputs = o.getJSONArray("inputs").let { a -> List(a.length()) { a.getJSONObject(it) } }
        // Phase 1 records kept each input's output on the input itself.
        val outputs = o.optJSONArray("outputs")?.let { a ->
            List(a.length()) { a.getJSONObject(it).let { x -> JobOutput(x.getString("uri"), x.getString("name"), Format.valueOf(x.getString("format"))) } }
        } ?: inputs.mapNotNull { x ->
            x.optString("outputUri").ifEmpty { null }?.let { JobOutput(it, x.optString("outputNameDone").ifEmpty { x.getString("name") }, target) }
        }
        return JobRecord(
            id = o.getString("id"),
            createdAt = o.getLong("createdAt"),
            op = JobOp.valueOf(o.optString("op", JobOp.CONVERT.name)),
            target = target,
            preset = Preset(
                quality = o.getInt("quality"),
                maxSide = o.getInt("maxSide"),
                keepMetadata = o.getBoolean("keepMetadata"),
                outputName = o.optString("outputName").ifEmpty { null },
                dpi = o.optInt("dpi", 150),
                pageSize = PageSize.valueOf(o.optString("pageSize", PageSize.A4.name)),
            ),
            inputs = inputs.map { x ->
                JobInput(
                    name = x.getString("name"),
                    format = x.optString("format").ifEmpty { null }?.let(Format::valueOf),
                    done = x.optBoolean("done", x.has("outputUri")),
                    pagesDone = x.optInt("pagesDone", 0),
                    error = x.optString("error").ifEmpty { null },
                    note = x.optString("note").ifEmpty { null },
                    needsPassword = x.optBoolean("needsPassword", false),
                )
            },
            outputs = outputs,
            pages = o.optJSONArray("pages")?.let(::pagesFrom).orEmpty(),
            splits = o.optJSONArray("splits")?.let { a -> List(a.length()) { pagesFrom(a.getJSONArray(it)) } }.orEmpty(),
            removePassword = o.optBoolean("removePassword", false),
        )
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
