package io.github.timefreak7.fileforge.jobs

import java.util.concurrent.ConcurrentHashMap

/**
 * S14: PDF passwords, per job and input, held only in this process's memory: never in job.json, the
 * WorkManager database or logs. They go when the job finishes, is removed, or the app process ends;
 * a job resumed after that asks again.
 */
object PdfPasswords {
    private val byJob = ConcurrentHashMap<String, Map<Int, String>>()

    fun add(jobId: String, passwords: Map<Int, String>) {
        if (passwords.isNotEmpty()) byJob.merge(jobId, passwords) { old, new -> old + new }
    }

    fun get(jobId: String, input: Int): String? = byJob[jobId]?.get(input)

    fun clear(jobId: String) {
        byJob.remove(jobId)
    }
}
