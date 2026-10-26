package io.github.timefreak7.fileforge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.timefreak7.fileforge.jobs.IncomingFile
import io.github.timefreak7.fileforge.jobs.JobQueue
import io.github.timefreak7.fileforge.ui.AppRoot
import io.github.timefreak7.fileforge.ui.FileForgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one activity: launcher, share target and Open-with target. */
class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<List<IncomingFile>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            FileForgeTheme {
                AppRoot(incoming = incoming, onIncomingHandled = { incoming = null }, debugTools = BuildConfig.DEBUG)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            incoming = withContext(Dispatchers.IO) { uris.map { JobQueue.describe(this@MainActivity, it) } }
        }
    }
}
