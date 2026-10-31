package io.github.timefreak7.fileforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** One protected file to ask about; [error] is shown under its field (e.g. after a wrong password). */
data class PasswordRequest(val name: String, val error: String?)

/**
 * Asks for the passwords of protected PDFs (S14: used for this job only, never saved). [offerRemove]
 * shows "Save without a password"; [note] explains anything else about the output.
 */
@Composable
fun PasswordDialog(
    files: List<PasswordRequest>,
    offerRemove: Boolean,
    note: String?,
    onSubmit: (passwords: List<String>, removePassword: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember(files) { mutableStateListOf(*Array(files.size) { "" }) }
    var remove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (files.size == 1) "This PDF is protected" else "These PDFs are protected") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the password to continue. FileForge uses it for this job only and never saves it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                files.forEachIndexed { i, file ->
                    OutlinedTextField(
                        value = values[i],
                        onValueChange = { values[i] = it },
                        label = { Text(file.name, maxLines = 1) },
                        isError = file.error != null,
                        supportingText = file.error?.let { { Text(it) } },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (offerRemove) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = remove, role = Role.Checkbox, onValueChange = { remove = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = remove, onCheckedChange = null)
                        Column {
                            Text("Save without a password", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Off keeps the same password on the new PDF.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = values.all { it.isNotEmpty() }, onClick = { onSubmit(values.toList(), remove) }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
