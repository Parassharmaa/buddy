package dev.buddy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.buddy.core.Conversation
import dev.buddy.core.RunState
import java.text.DateFormat
import java.util.Date

@Composable internal fun Activity(history: List<Conversation>, open: (String) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = history.firstOrNull { it.id == selectedId }
    BackHandler(enabled = selected != null) { selectedId = null }
    LazyColumn(
        Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Heading("Activity") }
        if (selected != null) {
            item {
                TextButton(onClick = { selectedId = null }) { Text("Back to activity") }
                Text(selected.title, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { open(selected.id) }) { Text("Open conversation") }
                Text("Task history", style = MaterialTheme.typography.titleMedium)
            }
            if (selected.runs.isEmpty()) item { Text("No tasks recorded in this conversation.") }
            items(selected.runs.asReversed(), key = { it.id }) { run ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(run.state.displayName(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            DateFormat.getDateTimeInstance(
                                DateFormat.MEDIUM,
                                DateFormat.SHORT
                            ).format(Date(run.createdAt))
                        )
                        Text("Request", style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(run.request) }
                        Text("Recorded outcome", style = MaterialTheme.typography.labelLarge)
                        SelectionContainer { Text(run.summary) }
                        if (run.evidenceIds.isNotEmpty()) {
                            Text("Recorded evidence IDs", style = MaterialTheme.typography.labelLarge)
                            SelectionContainer { Text(run.evidenceIds.take(64).joinToString("\n")) }
                            Text("References identify saved evidence; an ID alone does not verify an outcome.")
                        }
                    }
                }
            }
        } else if (history.isEmpty()) {
            item {
                Text("A clear record of what happened.", style = MaterialTheme.typography.titleLarge)
                Text("Your conversations and task outcomes will appear here.", Modifier.padding(top = 8.dp))
            }
        }
        if (selected == null) {
            items(history, key = { it.id }) { conversation ->
                Card(onClick = {
                    open(conversation.id)
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(conversation.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            conversation.runs.lastOrNull()?.summary ?: "Conversation",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { selectedId = conversation.id }) { Text("View task history") }
                    }
                }
            }
        }
    }
}

private fun RunState.displayName(): String = when (this) {
    RunState.QUEUED -> "Queued"
    RunState.PREPARING -> "Preparing"
    RunState.RUNNING -> "Running"
    RunState.PAUSING -> "Pausing"
    RunState.PAUSED -> "Paused"
    RunState.WAITING_FOR_USER -> "Waiting for you"
    RunState.WAITING_FOR_APPROVAL -> "Waiting for approval"
    RunState.HANDED_TO_USER -> "Handed to you"
    RunState.STOPPING -> "Stopping"
    RunState.VERIFYING -> "Verifying"
    RunState.DONE -> "Done"
    RunState.PARTIAL -> "Partially completed"
    RunState.BLOCKED -> "Blocked"
    RunState.CANCELLED -> "Cancelled"
    RunState.INTERRUPTED -> "Interrupted"
    RunState.UNKNOWN -> "Outcome unknown"
}
