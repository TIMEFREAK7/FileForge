package io.github.timefreak7.fileforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Line(val ok: Boolean?, val text: String) {
    val mark get() = when (ok) { true -> "✅"; false -> "❌"; null -> "ℹ️" }
}

/** Debug-build screen that runs [SelfTest]: a summary with progress on top, every result below. */
@Composable
fun SelfTestScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<Line>() }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(1) }
    var step by remember { mutableStateOf("") }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }

    // The full run takes many minutes; the screen must not lock part-way.
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    fun start(fuzz: Int) {
        running = true
        finished = false
        lines.clear()
        done = 0
        startedAt = System.currentTimeMillis()
        now = startedAt
        scope.launch {
            withContext(Dispatchers.Default) {
                SelfTest(
                    context,
                    report = { ok, text -> scope.launch { lines += Line(ok, text) } },
                    progress = { d, t, s -> scope.launch { done = d; total = t; step = s } },
                ).run(fuzz)
            }
            now = System.currentTimeMillis()
            running = false
            finished = true
        }
    }

    val failed = lines.filter { it.ok == false }
    val keyPrefixes = listOf("100 mixed", "Speed", "Fuzz", "S7", "S8", "Merge 300", "Split 300", "Scanned PDF", "JPEG encode", "Gate:", "S14")
    val key = lines.filter { line -> line.ok != false && keyPrefixes.any { line.text.startsWith(it) } }
    val elapsed = (now - startedAt).coerceAtLeast(0) / 1000

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Self-tests", style = MaterialTheme.typography.headlineMedium) }
            item {
                Text(
                    "Quick takes a few minutes. Full adds the 1,000-file fuzz pass per format and can take 20–40 minutes. The screen stays on by itself; don't press the power button or leave the app, or Android pauses the test.",
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
            if (running || finished) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val percent = (done * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)
                            val passed = lines.count { it.ok == true }
                            Text(
                                when {
                                    running -> "Running: $percent%"
                                    failed.isEmpty() -> "✅ All $passed checks passed"
                                    else -> "❌ ${failed.size} of ${passed + failed.size} checks failed"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                            Text(
                                (if (running) "$step\n" else "") + "$passed passed, ${failed.size} failed · ${elapsed / 60} min ${elapsed % 60} s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            (failed + key).forEach { Text("${it.mark} ${it.text}", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
            if (lines.isNotEmpty()) item { Text("All results", style = MaterialTheme.typography.titleMedium) }
            items(lines) { Text("${it.mark} ${it.text}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
