package io.github.timefreak7.fileforge.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.timefreak7.fileforge.core.Format
import io.github.timefreak7.fileforge.jobs.AppSettings

/** Human-readable folder path from a tree URI, e.g. "Documents/Converted". */
fun folderLabel(tree: Uri?): String =
    tree?.let { runCatching { DocumentsContract.getTreeDocumentId(it).substringAfter(':') }.getOrNull() }
        ?.ifEmpty { "Internal storage" } ?: "Not chosen"

@Composable
fun SettingsScreen(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onChooseFolder: () -> Unit,
    onSelfTest: (() -> Unit)?,
    onSignatures: () -> Unit,
) {
    val context = LocalContext.current
    var keepMetadata by remember { mutableStateOf(settings.keepMetadata) }
    var maxSide by remember { mutableIntStateOf(settings.defaultMaxSide) }
    var order by remember { mutableStateOf(settings.donutOrder) }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("Output folder")
        Text(folderLabel(settings.outputTree), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onChooseFolder, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change folder") }

        HorizontalDivider()
        Section("Photo details")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Keep date, camera and location by default",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = keepMetadata, onCheckedChange = { keepMetadata = it; settings.keepMetadata = it })
        }
        Text(
            "Off (recommended) removes them, so a shared photo does not reveal where it was taken.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Section("Default size")
        // Wraps: four chips do not fit one line on a phone at larger font sizes.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SizeChoices.forEach { px ->
                FilterChip(
                    selected = maxSide == px,
                    onClick = { maxSide = px; settings.defaultMaxSide = px },
                    label = { Text(sizeLabel(px)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }

        HorizontalDivider()
        Section("Default quality")
        Format.entries.filter { it.hasQuality }.forEach { format ->
            var q by remember { mutableFloatStateOf(settings.quality(format).toFloat()) }
            val label = if (format == Format.PDF) "Images in PDFs" else format.label
            Text("$label: ${q.toInt()}", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = q,
                onValueChange = { q = it },
                onValueChangeFinished = { settings.setQuality(format, q.toInt()) },
                valueRange = 10f..100f,
                steps = 17,
            )
        }

        HorizontalDivider()
        Section("Donut order")
        Text(
            "Positions stay fixed so your swipes stay the same. The first one sits at the top, then clockwise.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        order.forEachIndexed { i, format ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${format.label}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = i > 0,
                    onClick = { order = order.swap(i, i - 1); settings.donutOrder = order },
                ) { Text("Up") }
                TextButton(
                    enabled = i < order.lastIndex,
                    onClick = { order = order.swap(i, i + 1); settings.donutOrder = order },
                ) { Text("Down") }
            }
        }

        HorizontalDivider()
        Section("Signatures")
        OutlinedButton(onClick = onSignatures, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Manage signatures") }

        HorizontalDivider()
        Section("About")
        Text("FileForge $version · works fully offline", style = MaterialTheme.typography.bodyLarge)
        Text(LICENCES, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onSelfTest != null) {
            Button(onClick = onSelfTest, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Run self-tests (debug build)")
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private fun <T> List<T>.swap(a: Int, b: Int) = toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }

private const val LICENCES = """Open-source licences
FileForge: MIT licence. Source: github.com/TIMEFREAK7/FileForge
AndroidX libraries (Compose, Material 3, WorkManager, HeifWriter, ExifInterface): Apache License 2.0
Kotlin and kotlinx.coroutines: Apache License 2.0"""
