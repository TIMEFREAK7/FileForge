package io.github.timefreak7.fileforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.core.PageSize
import io.github.timefreak7.fileforge.core.Preset
import io.github.timefreak7.fileforge.core.Targets

/** Longest-side choices offered everywhere a size is picked; 0 = original. */
val SizeChoices = listOf(0, 4000, 2000, 1080)

fun sizeLabel(px: Int) = if (px == 0) "Original" else "$px px"

/** Page detail for rendering PDF pages and for images kept inside a compressed PDF. */
private val DpiChoices = listOf(72 to "Small (72 dpi)", 150 to "Standard (150 dpi)", 300 to "High (300 dpi)")

/** The options sheet (spec: hold on a segment). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(
    target: Format,
    inputs: List<Format?>,
    defaults: Preset,
    onConvert: (Preset) -> Unit,
    onDismiss: () -> Unit,
) {
    var quality by remember { mutableFloatStateOf(defaults.quality.toFloat()) }
    var maxSide by remember { mutableIntStateOf(defaults.maxSide) }
    var keepMetadata by remember { mutableStateOf(defaults.keepMetadata) }
    var name by remember { mutableStateOf("") }
    var dpi by remember { mutableIntStateOf(defaults.dpi) }
    var pageSize by remember { mutableStateOf(defaults.pageSize) }
    val fileCount = inputs.size
    val fromPdf = inputs.any { it == Format.PDF }
    val merge = fromPdf && target == Format.PDF && fileCount > 1
    val showQuality = target.hasQuality && !merge
    val showSize = !fromPdf
    val showMetadata = !fromPdf && target != Format.PDF

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "${actionText(target, Targets.action(inputs, target))}: ${if (fileCount == 1) "1 file" else "$fileCount files"}",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (showQuality) {
                Text(if (target == Format.PDF) "Image quality: ${quality.toInt()}" else "Quality: ${quality.toInt()}", style = MaterialTheme.typography.titleSmall)
                Slider(value = quality, onValueChange = { quality = it }, valueRange = 10f..100f, steps = 17)
            }
            if (showSize) {
                Text("Size (longest side)", style = MaterialTheme.typography.titleSmall)
                Chips(SizeChoices.map { it to sizeLabel(it) }, maxSide) { maxSide = it }
            }
            if (fromPdf && !merge) {
                Text(if (target == Format.PDF) "Image detail" else "Page detail", style = MaterialTheme.typography.titleSmall)
                Chips(DpiChoices, dpi) { dpi = it }
            }
            if (!fromPdf && target == Format.PDF) {
                Text("Page size", style = MaterialTheme.typography.titleSmall)
                Chips(PageSize.entries.map { it to it.label }, pageSize) { pageSize = it }
            }
            if (showMetadata && target != Format.HEIC) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep photo details", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Date, camera and location. Off removes them, including where the photo was taken.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = keepMetadata, onCheckedChange = { keepMetadata = it })
                }
            } else if (showMetadata) {
                Text(
                    "HEIC files are saved without photo details (date, camera, location).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (fileCount == 1 || target == Format.PDF) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text("File name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    onConvert(
                        Preset(
                            quality = quality.toInt().coerceIn(1, 100),
                            maxSide = maxSide,
                            keepMetadata = keepMetadata && showMetadata && target != Format.HEIC,
                            outputName = name.trim().ifEmpty { null },
                            dpi = dpi,
                            pageSize = pageSize,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) { Text(if (target == Format.PDF && fromPdf) Targets.action(inputs, target) else "Convert") }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** One-choice chip row; wraps, since several chips do not fit one line at larger font sizes. */
@Composable
private fun <T> Chips(choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { (value, text) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(text) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

/** Plain list that replaces the donut when TalkBack or Switch Access is on (spec: Accessibility). */
@Composable
fun TargetListDialog(
    targets: List<Format>,
    fileCount: Int,
    label: (Format) -> String,
    onConvert: (Format) -> Unit,
    onOptions: (Format) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Convert ${if (fileCount == 1) "1 file" else "$fileCount files"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (targets.isEmpty()) Text("FileForge cannot convert these files.")
                targets.forEach { format ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onConvert(format) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(actionText(format, label(format)))
                        }
                        OutlinedButton(onClick = { onOptions(format) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Options")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
