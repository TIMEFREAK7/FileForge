package io.github.timefreak7.fileforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.jobs.JobOp
import io.github.timefreak7.fileforge.jobs.JobQueue
import io.github.timefreak7.fileforge.jobs.JobView

@Composable
fun JobsScreen(jobs: List<JobView>, contentPadding: PaddingValues, onEnterPassword: (String) -> Unit) {
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
        item { Text("Jobs", style = MaterialTheme.typography.headlineMedium) }
        if (jobs.isEmpty()) {
            item { Text("No conversions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(jobs, key = { it.record.id }) { job ->
            val record = job.record
            val failed = record.failed
            val converted = record.inputs.count { it.done }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val files = if (record.inputs.size == 1) record.inputs[0].name else "${record.inputs.size} files"
                    val result = when (record.op) {
                        JobOp.CONVERT -> record.target.label
                        JobOp.PDF_TO_IMAGES -> "${record.target.label} pages"
                        JobOp.COMBINE_PDF -> if (record.inputs.all { it.format == Format.PDF }) "Merged PDF" else "PDF"
                        JobOp.COMPRESS_PDF -> "Smaller PDF"
                        JobOp.ASSEMBLE_PDF -> "Edited PDF"
                        JobOp.SPLIT_PDF -> "${record.splits.size} PDFs"
                        JobOp.EDIT_PDF -> "Marked-up PDF"
                    }
                    Text("$files → $result", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (job.state) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting to start"
                            WorkInfo.State.RUNNING -> "Converting: ${job.done} of ${job.total}"
                            WorkInfo.State.CANCELLED -> "Cancelled after $converted of ${job.total}"
                            else -> if (failed.isEmpty()) "Done: ${record.outputs.size} saved" else "${record.outputs.size} saved, ${failed.size} failed"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (job.state == WorkInfo.State.RUNNING) {
                        LinearProgressIndicator(
                            progress = { if (job.total == 0) 0f else job.done.toFloat() / job.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    record.inputs.mapNotNull { it.note }.distinct().forEach { note ->
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    failed.take(5).forEach { input ->
                        Text("${input.name}: ${input.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (failed.size > 5) {
                        Text("…and ${failed.size - 5} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (job.running) {
                            TextButton(onClick = { JobQueue.cancel(context, record.id) }) { Text("Cancel") }
                        } else {
                            val retryable = failed.any { it.format != null } || (job.state == WorkInfo.State.CANCELLED && converted < job.total)
                            when {
                                failed.any { it.needsPassword } -> TextButton(onClick = { onEnterPassword(record.id) }) { Text("Enter password") }
                                retryable -> TextButton(onClick = { JobQueue.retry(context, record.id) }) { Text("Retry") }
                            }
                            TextButton(onClick = { JobQueue.dismiss(context, record.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}
