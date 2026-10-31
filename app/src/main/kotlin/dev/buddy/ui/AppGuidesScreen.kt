package dev.buddy.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.buddy.core.guides.GuideLookup
import dev.buddy.guides.InstalledAppGuides

@Composable
internal fun AppGuidesScreen(shareEnabled: Boolean, useWithModel: (String) -> Unit) {
    val context = LocalContext.current
    val guide by produceState<GuideLookup?>(null, context) {
        value = runCatching { InstalledAppGuides.current(context) }.getOrDefault(GuideLookup.Unavailable)
    }
    Text("Bundled interface facts for supported app versions. Guides never grant permission to act.")
    when (val current = guide) {
        is GuideLookup.Available -> {
            Text(current.guide.title, style = MaterialTheme.typography.titleMedium)
            Text("Matches your installed version", style = MaterialTheme.typography.labelMedium)
            SelectionContainer { Text(current.guide.text) }
            TextButton(onClick = { useWithModel(current.guide.id) }, enabled = shareEnabled) { Text("Use with model") }
            Text("Review the guide text and model destination before attaching it.")
        }

        GuideLookup.VersionChanged -> Text("This guide needs an update for your installed app version.")

        GuideLookup.Unavailable -> Text("No bundled guide is available for this app.")

        null -> Text("Checking the installed version…")
    }
}
