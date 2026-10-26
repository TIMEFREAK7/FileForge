package io.github.timefreak7.fileforge

import android.app.Application
import io.github.timefreak7.fileforge.jobs.JobQueue
import kotlin.concurrent.thread

class FileForgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // This class also starts in the parser processes; only the main process may touch job files.
        if (getProcessName() != packageName) return
        // Spec (Storage): temporary files go at every app start.
        thread(name = "cleanup") { runCatching { JobQueue.cleanUp(this) } }
    }
}
