package dev.buddy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.buddy.BuddyState
import dev.buddy.BuddyViewModel

@Composable internal fun ModelSettings(state: BuddyState, model: BuddyViewModel) {
    var base by rememberSaveable(state.modelBase) { mutableStateOf(state.modelBase) }
    var name by rememberSaveable(state.modelName) { mutableStateOf(state.modelName) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val busy = state.testingModel || state.modelCatalog.loading
    val catalog = state.modelCatalog.takeIf { it.base == base.trim().trimEnd('/') }
    Text("Bring your own model", style = MaterialTheme.typography.titleLarge)
    Text(
        "Use an OpenAI-compatible chat API with streaming and tool calling. Task-relevant text and screen content may go directly to this provider when those capabilities are enabled."
    )
    FlowRow {
        TextButton(onClick = {
            base = "https://api.openai.com/v1"
            key = ""
            showKey = false
        }, enabled = !busy) { Text("OpenAI") }
        TextButton(onClick = {
            base = ""
            key = ""
            showKey = false
        }, enabled = !busy) { Text("Custom endpoint") }
    }
    OutlinedTextField(
        base,
        {
            base = it
            key = ""
            showKey = false
        },
        label = {
            Text("API base URL")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri
        ),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(name, {
        name = it
    }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        key,
        {
            key = it
        },
        label = {
            Text(if (state.configured) "API key · blank keeps saved key" else "API key")
        },
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide key" else "Show key") }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password
        ),
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "Use HTTPS, including for models on your local network. Requests go directly to this provider.",
        style = MaterialTheme.typography.bodySmall
    )
    LocalNetworkAccess("Models on your Wi-Fi network require local network access.")
    TextButton(
        onClick = { model.loadModels(base, key) },
        enabled = state.loaded && !busy &&
            !state.clearingData && base.isNotBlank()
    ) { Text(if (state.modelCatalog.loading) "Loading model IDs…" else "Load available models") }
    catalog?.let { Text(it.status, style = MaterialTheme.typography.bodySmall) }
    if (!catalog?.models.isNullOrEmpty()) {
        TextButton(onClick = { choosing = true }, enabled = !busy) { Text("Choose a model") }
    }
    if (choosing && catalog != null) {
        ModelPicker(catalog.models, {
            name = it
            choosing = false
        }, { choosing = false })
    }
    Button(
        onClick = {
            model.saveModel(base, name, key, false)
            key = ""
            showKey = false
        },
        enabled =
            state.loaded && !busy && !state.clearingData && base.isNotBlank() && name.isNotBlank()
    ) {
        if (state.testingModel) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (state.testingModel) "Testing model" else "Save and test model")
    }
    TextButton(
        onClick = {
            model.saveModel(base, name, key, false, saveUnverified = true)
            key = ""
            showKey = false
        },
        enabled = state.loaded && !busy && !state.clearingData && base.isNotBlank() && name.isNotBlank()
    ) { Text("Save anyway · Unverified") }
    Text(state.modelStatus)
}

@Composable internal fun ModelPicker(models: List<String>, select: (String) -> Unit, dismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    val matching = models.filter { it.contains(filter, ignoreCase = true) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Available models", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                filter,
                { filter = it.take(200) },
                label = { Text("Filter model IDs") },
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                items(matching.take(100), key = { it }) { id ->
                    TextButton(onClick = { select(id) }, modifier = Modifier.fillMaxWidth()) { Text(id) }
                }
            }
            if (matching.isEmpty()) Text("No matching IDs. You can enter one manually.")
            if (matching.size > 100) Text("Showing 100 matches. Refine the filter for more.")
            TextButton(onClick = dismiss) { Text("Close model list") }
        }
    }
}
