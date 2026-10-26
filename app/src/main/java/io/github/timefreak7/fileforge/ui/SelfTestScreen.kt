package io.github.timefreak7.fileforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-build screen that runs [SelfTest] and lists the results for a screenshot. */
@Composable
fun SelfTestScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }

    fun start(fuzz: Int) {
        running = true
        lines.clear()
        lines += "Running (fuzz $fuzz per format)…"
        scope.launch {
            withContext(Dispatchers.Default) {
                SelfTest(context) { ok, text ->
                    val mark = when (ok) { true -> "✅"; false -> "❌"; null -> "ℹ️" }
                    scope.launch { lines += "$mark $text" }
                }.run(fuzz)
            }
            val failed = lines.count { it.startsWith("❌") }
            lines += if (failed == 0) "Done: all checks passed." else "Done: $failed check(s) failed."
            running = false
        }
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Self-tests", style = MaterialTheme.typography.headlineMedium) }
            item {
                Text(
                    "Quick takes about a minute. Full runs the 1,000-file fuzz pass the spec asks for and can take 10–20 minutes; keep the screen on.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !running, onClick = { start(50) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Quick") }
                    Button(enabled = !running, onClick = { start(1000) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Full") }
                    OutlinedButton(enabled = !running, onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
                }
            }
            items(lines) { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
