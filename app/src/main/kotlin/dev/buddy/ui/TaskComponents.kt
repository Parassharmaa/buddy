package dev.buddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.buddy.core.RunRecord

@Composable internal fun Heading(text: String) {
    Text(
        text,
        Modifier.semantics {
            heading()
        }.padding(bottom = 16.dp),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable internal fun RunCard(run: RunRecord) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                run.state.name.lowercase().replace('_', ' ').replaceFirstChar {
                    it.uppercase()
                },
                style = MaterialTheme.typography.labelLarge
            )
            Text(run.summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
