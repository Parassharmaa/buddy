package dev.buddy.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.buddy.core.Conversation

@Composable internal fun Activity(history: List<Conversation>, open: (String) -> Unit) {
    LazyColumn(
        Modifier.widthIn(max = 760.dp).fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Heading("Activity") }
        if (history.isEmpty()) {
            item {
                Text("A clear record of what happened.", style = MaterialTheme.typography.titleLarge)
                Text("Your conversations and task outcomes will appear here.", Modifier.padding(top = 8.dp))
            }
        }
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
                }
            }
        }
    }
}
