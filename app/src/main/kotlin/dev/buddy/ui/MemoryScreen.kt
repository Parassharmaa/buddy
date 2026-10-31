package dev.buddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buddy.BuddyViewModel
import dev.buddy.core.MemoryPolicy

@Composable internal fun MemoryScreen(model: BuddyViewModel, taskActive: Boolean) {
    val state by model.memory.state.collectAsStateWithLifecycle()
    var clear by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<String?>(null) }
    val available = state.loaded && !state.busy && !taskActive
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = state.enabled,
            role = Role.Switch,
            enabled = available,
            onValueChange = model::setMemoryEnabled
        )
    ) {
        Text("Save preferences", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = state.enabled, onCheckedChange = null, enabled = available)
    }
    Text(
        "Off by default. Choose Remember under a message you wrote to save a preference. " +
            "Preferences stay on this device; Use with model opens a sharing review."
    )
    Text(
        "Saved preferences are separate from chat retention. Deleting one removes its saved edit history; " +
            "copies you shared in chats and with providers remain.",
        style = MaterialTheme.typography.bodySmall
    )
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("${state.memories.size} of ${MemoryPolicy.MAX_FACTS} preferences")
    state.memories.forEach { memory ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(memory.fact)
                FlowRow {
                    TextButton(onClick = { model.memory.edit(memory.id) }, enabled = available) { Text("Edit") }
                    TextButton(onClick = { model.deleteMemory(memory.id) }, enabled = available) { Text("Delete") }
                    TextButton(
                        onClick = { model.sources.memory(memory.id) },
                        enabled = available && state.enabled
                    ) { Text("Use with model") }
                    if (memory.revisions.isNotEmpty()) {
                        TextButton(onClick = { history = if (history == memory.id) null else memory.id }) {
                            Text("Recent edits")
                        }
                    }
                }
                if (history == memory.id) {
                    memory.revisions.asReversed().forEach { revision ->
                        Text(revision.fact, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    TextButton(onClick = { clear = true }, enabled = available) { Text("Delete saved memories") }
    if (clear) {
        AlertDialog(
            onDismissRequest = { clear = false },
            title = { Text("Delete saved memories?") },
            text = {
                Text("Remove saved preferences and their edit history. Shared copies in chats and at providers remain.")
            },
            confirmButton = {
                TextButton(onClick = {
                    clear = false
                    model.clearMemories()
                }) { Text("Delete memories") }
            },
            dismissButton = { TextButton(onClick = { clear = false }) { Text("Keep memories") } }
        )
    }
}

@Composable internal fun MemoryEditorDialog(controller: MemoryController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val editor = state.editor ?: return
    var fact by remember(editor.id) { mutableStateOf(editor.fact) }
    AlertDialog(
        onDismissRequest = controller::dismissEditor,
        title = { Text(if (editor.memoryId == null) "Remember this preference?" else "Edit preference") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.enabled && editor.memoryId == null) Text("Saving turns memories on.")
                Text("Save a preference you have stated. Sharing it with a model requires a separate review.")
                OutlinedTextField(
                    value = fact,
                    onValueChange = { if (it.length <= MemoryPolicy.MAX_CHARACTERS) fact = it },
                    label = { Text("Preference") },
                    supportingText = { Text("${fact.length}/${MemoryPolicy.MAX_CHARACTERS}") },
                    modifier = Modifier.fillMaxWidth()
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                controller.save(fact)
            }, enabled = !state.busy && fact.isNotBlank()) { Text("Save preference") }
        },
        dismissButton = {
            TextButton(onClick = controller::dismissEditor, enabled = !state.busy) { Text("Keep in chat") }
        }
    )
}
