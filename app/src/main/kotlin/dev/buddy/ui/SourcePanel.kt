package dev.buddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buddy.BuddyState

@Composable internal fun SourcePanel(state: BuddyState, controller: SourceController) {
    val selection by controller.state.collectAsStateWithLifecycle()
    if (selection.preparing) {
        Text("Preparing a local text preview…")
        TextButton(onClick = { controller.cancel() }) { Text("Cancel preview") }
    }
    if (state.draftSources.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Text for your next message", style = MaterialTheme.typography.titleSmall)
            state.draftSources.forEach { source ->
                Text("${source.label} · ${source.text.length} characters")
                TextButton(onClick = {
                    controller.remove(source.id)
                }, enabled = !state.active) { Text("Remove source") }
            }
            Text(
                "Sent when you press Send, only to the provider and model you confirmed.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable internal fun SourceConsentDialog(controller: SourceController, ready: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val pending = state.pending ?: return
    AlertDialog(
        onDismissRequest = { controller.cancel() },
        title = { Text("Use this text with your model?") },
        text = {
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Provider: ${pending.provider}\nModel: ${pending.model}")
                Text(pending.source.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    "This copy is attached to your next message and sent when you press Send. " +
                        "It stays in this chat history until you delete the chat or its retention expires."
                )
                if (pending.source.truncated) Text("Only the following excerpt will be shared.")
                SelectionContainer { Text(pending.source.text) }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (controller.confirm()) ready() }) { Text("Attach text") }
        },
        dismissButton = { TextButton(onClick = { controller.cancel() }) { Text("Keep local") } }
    )
}
