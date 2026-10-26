package io.github.timefreak7.fileforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import io.github.timefreak7.fileforge.core.Preset

/** Longest-side choices offered everywhere a size is picked; 0 = original. */
val SizeChoices = listOf(0, 4000, 2000, 1080)

fun sizeLabel(px: Int) = if (px == 0) "Original" else "$px px"

/** The options sheet (spec: hold on a segment). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(
    target: Format,
    fileCount: Int,
    defaults: Preset,
    onConvert: (Preset) -> Unit,
    onDismiss: () -> Unit,
) {
    var quality by remember { mutableFloatStateOf(defaults.quality.toFloat()) }
    var maxSide by remember { mutableIntStateOf(defaults.maxSide) }
    var keepMetadata by remember { mutableStateOf(defaults.keepMetadata) }
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Convert ${if (fileCount == 1) "1 file" else "$fileCount files"} to ${target.label}",
                style = MaterialTheme.typography.headlineSmall,
            )
            if (target.hasQuality) {
                Text("Quality: ${quality.toInt()}", style = MaterialTheme.typography.titleSmall)
                Slider(value = quality, onValueChange = { quality = it }, valueRange = 10f..100f, steps = 17)
            }
            Text("Size (longest side)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SizeChoices.forEach { px ->
                    FilterChip(
                        selected = maxSide == px,
                        onClick = { maxSide = px },
                        label = { Text(sizeLabel(px)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            if (target != Format.HEIC) {
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
            } else {
                Text(
                    "HEIC files are saved without photo details (date, camera, location).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (fileCount == 1) {
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
                            keepMetadata = keepMetadata && target != Format.HEIC,
                            outputName = name.trim().ifEmpty { null },
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) { Text("Convert") }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** Plain list that replaces the donut when TalkBack or Switch Access is on (spec: Accessibility). */
@Composable
fun TargetListDialog(
    targets: List<Format>,
    fileCount: Int,
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
                            Text("Convert to ${format.label}")
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
