package dev.buddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.buddy.BuddyState

@Composable internal fun Chat(
    state: BuddyState,
    settings: () -> Unit,
    draft: (String) -> Unit,
    files: @Composable () -> Unit = {}
) {
    LazyColumn(
        Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = settings, label = {
                    Text(if (state.configured) state.modelName else "Choose a model")
                })
                AssistChip(onClick = settings, label = { Text("Phone setup") })
            }
        }
        if (state.loaded) item { files() }
        if (state.conversation.messages.isEmpty() && state.artifacts.isEmpty()) {
            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    "A little help.\nMore room for you.",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Think through a question, make a plan, or get something started.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Your own assistant, your choice of model", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Connect a model API to start chatting. Your requests go directly to the provider you choose."
                        )
                        FilledTonalButton(onClick = settings) { Text("Set up Buddy") }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A place to start", style = MaterialTheme.typography.labelLarge)
                    listOf(
                        "Help me plan a focused day",
                        "Break a big project into steps",
                        "Explain something I’m learning"
                    ).forEach { suggestion ->
                        OutlinedButton(onClick = {
                            draft(suggestion)
                        }, modifier = Modifier.fillMaxWidth()) { Text(suggestion) }
                    }
                }
            }
            item {
                Text(
                    "Development build · Background phone control is awaiting device qualification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(state.conversation.messages.withIndex().toList(), key = { it.index }) { indexed ->
            val message = indexed.value
            if (message.content !=
                null
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (message.role ==
                        "user"
                    ) {
                        Alignment.End
                    } else {
                        Alignment.Start
                    }
                ) {
                    Text(
                        if (message.role ==
                            "user"
                        ) {
                            "You"
                        } else {
                            "Buddy"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.role ==
                                "user"
                            ) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        )
                    ) {
                        SelectionContainer {
                            Text(
                                requireNotNull(message.content),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
        state.conversation.runs.lastOrNull()?.let { run -> item { RunCard(run) } }
    }
}

@Composable internal fun Composer(state: BuddyState, change: (String) -> Unit, send: () -> Unit, stop: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(value = state.draft, onValueChange = change, label = {
                Text(if (state.active) "Add to your request" else "Ask Buddy")
            }, modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(24.dp))
            Button(
                onClick = send,
                enabled =
                    state.loaded && !state.importing && !state.exporting && state.draft.isNotBlank() &&
                        state.draft.length <= 32_768,
                modifier = Modifier.height(56.dp)
            ) {
                Text("Send")
            }
        }
        if (state.active) {
            Row(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Working with your model", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = stop) { Text("Stop") }
            }
        }
    }
}
