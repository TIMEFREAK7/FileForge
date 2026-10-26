package io.github.timefreak7.fileforge.ui

import android.net.Uri
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.jobs.IncomingFile
import io.github.timefreak7.fileforge.jobs.JobView

/** A converted file shown in Recents. */
data class RecentOutput(val uri: Uri, val name: String, val format: Format, val createdAt: Long)

fun recentOutputs(jobs: List<JobView>): List<RecentOutput> = jobs
    .flatMap { job ->
        job.record.inputs.mapNotNull { input ->
            input.outputUri?.let { RecentOutput(Uri.parse(it), input.outputName ?: input.name, job.record.target, job.record.createdAt) }
        }
    }
    .take(50)

/** Long-press gesture callbacks; positions are in root (screen) pixels. */
class DonutDrag(
    val onStart: (IncomingFile, Offset) -> Unit,
    val onMove: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

@Composable
fun HomeScreen(
    recents: List<RecentOutput>,
    contentPadding: PaddingValues,
    onPickFiles: () -> Unit,
    drag: DonutDrag,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("FileForge", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text("Your Files, Your Way.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Button(onClick = onPickFiles, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Pick files", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Text("Recent", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
        }
        if (recents.isEmpty()) {
            item {
                Text(
                    "Converted files show up here. Long-press one to convert it again. " +
                        "You can also share photos to FileForge from any app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(recents, key = { it.uri.toString() }) { item ->
            var origin by remember { mutableStateOf(Offset.Zero) }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { origin = it.positionInRoot() }
                    .pointerInput(item.uri) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { p -> drag.onStart(IncomingFile(item.uri, item.name, item.format), origin + p) },
                            onDrag = { change, _ -> drag.onMove(origin + change.position) },
                            onDragEnd = drag.onEnd,
                            onDragCancel = drag.onCancel,
                        )
                    }
                    .semantics { contentDescription = "${item.name}. Long-press to convert again." },
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.format.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { Outputs.launch(context, Outputs.viewIntent(item.uri, item.format.mime)) }) { Text("Open") }
                    TextButton(onClick = { Outputs.launch(context, Outputs.shareIntent(item.uri, item.format.mime)) }) { Text("Share") }
                }
            }
        }
    }
}
