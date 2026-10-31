package dev.buddy.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.buddy.browser.WebReadResult
import dev.buddy.browser.WebReadStatus
import dev.buddy.browser.WebStatus
import dev.buddy.storage.WorkspaceArtifact

@Composable
internal fun BrowserScreen(controller: BrowserController, files: List<WorkspaceArtifact>, useWithModel: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var address by remember { mutableStateOf(state.web.address) }
    var editingAddress by remember { mutableStateOf(false) }
    LaunchedEffect(state.web.address, editingAddress) {
        if (!editingAddress && state.web.address.isNotEmpty()) address = state.web.address
    }
    var selectedFiles by remember(state.web.hasUploadRequest) { mutableStateOf(setOf<String>()) }
    DisposableEffect(context) {
        var owner: Context = context
        while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
        val window = (owner as? Activity)?.window
        val secure = window?.attributes?.flags?.let { it and WindowManager.LayoutParams.FLAG_SECURE != 0 } ?: false
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            controller.detach()
            if (!secure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("Browser")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                address,
                { address = it },
                label = { Text("Website address") },
                singleLine = true,
                modifier = Modifier.weight(1f).onFocusChanged { editingAddress = it.isFocused },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { controller.open(context, address) })
            )
            Button(
                onClick = { controller.open(context, address) },
                enabled =
                    address.isNotBlank() && address.length <= 4096
            ) { Text("Open") }
        }
        if (state.web.address.isNotEmpty()) {
            Text(
                state.web.address,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
        FlowRow {
            TextButton(
                onClick = useWithModel,
                enabled = state.web.canRead && state.web.status == WebStatus.READY
            ) { Text("Use with model") }
            TextButton(
                onClick = controller::read,
                enabled =
                    state.web.status == WebStatus.READY && state.web.canRead && !state.reading
            ) {
                Text(if (state.reading) "Reading page…" else "Read page")
            }
            TextButton(onClick = { controller.sound(!state.web.soundAllowed) }, enabled = state.web.canMute) {
                Text(if (state.web.soundAllowed) "Mute audio" else "Allow audio")
            }
            TextButton(onClick = controller::close, enabled = state.hasSession) { Text("Close page") }
        }
        if (state.web.status == WebStatus.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.web.pendingOrigin != null) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("Continue to ${state.web.pendingOrigin}?")
                    Row {
                        TextButton(onClick = controller::allowNavigation) { Text("Continue") }
                        TextButton(onClick = controller::cancelNavigation) { Text("Stay here") }
                    }
                }
            }
        }
        state.error?.let { error ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text(error)
                    TextButton(onClick = controller::dismissError) { Text("Dismiss") }
                }
            }
        }
        when (val result = state.result) {
            is WebReadResult.Allowed -> Card {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(12.dp)
                ) {
                    Text("Local page preview", style = MaterialTheme.typography.titleSmall)
                    Text(result.snapshot.sourceLabel, style = MaterialTheme.typography.bodySmall)
                    if (result.snapshot.truncated) {
                        Text(
                            "Excerpt of the page",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    SelectionContainer { Text(result.snapshot.text) }
                }
            }

            is WebReadResult.Suppressed -> Text(
                if (result.status ==
                    WebReadStatus.PRIVATE
                ) {
                    "This page needs your control. Private fields are not collected."
                } else {
                    "This page could not be read safely. You can use the browser manually."
                }
            )

            null -> Unit
        }
        if (state.web.status == WebStatus.RENDERER_GONE || state.web.status == WebStatus.FAILED) {
            Text(
                "The page stopped. Check the website if an action was in progress; Buddy will not repeat it automatically."
            )
        }
        if (state.hasSession && state.web.status != WebStatus.RENDERER_GONE) {
            AndroidView(
                factory = { controller.attach(it) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                update = { if (it.isAttachedToWindow) controller.loadPending() },
                onRelease = { controller.detach() }
            )
        } else {
            Text("A private place to browse", style = MaterialTheme.typography.titleLarge)
            Text(
                "Open a website without pairing your phone. Read page creates a local text preview; it does not send page contents to your model."
            )
        }
    }
    if (state.web.hasUploadRequest) {
        val accepts = controller.acceptedUploadTypes().flatMap { it.split(',') }.map { it.trim().lowercase() }
        val available = files.filter { file ->
            accepts.isEmpty() || accepts.any { type ->
                type.isBlank() || type == "*/*" || type == file.mimeType ||
                    (type.endsWith("/*") && file.mimeType.startsWith(type.substringBefore('/') + "/")) ||
                    (type.startsWith('.') && file.displayName.endsWith(type, ignoreCase = true))
            }
        }
        AlertDialog(
            onDismissRequest = controller::cancelUpload,
            title = { Text("Share a file with this website?") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(state.web.address.substringBefore('?').substringBefore('#'))
                    Text("The website can upload and keep the files you choose.")
                    if (available.isEmpty()) Text("Add a matching file to this conversation first.")
                    available.forEach { file ->
                        Row {
                            Checkbox(file.id in selectedFiles, { checked ->
                                selectedFiles = if (!checked) {
                                    selectedFiles - file.id
                                } else if (controller.acceptsMultipleUploads()) {
                                    selectedFiles + file.id
                                } else {
                                    setOf(file.id)
                                }
                            })
                            Text(file.displayName, Modifier.padding(top = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.chooseFiles(
                        available.filter { it.id in selectedFiles }.map { file ->
                            Uri.Builder().scheme("content").authority(context.packageName + ".artifacts")
                                .appendPath(file.workspace).appendPath(file.id).build()
                        }
                    )
                }, enabled = selectedFiles.isNotEmpty()) { Text("Share selected files") }
            },
            dismissButton = { TextButton(onClick = controller::cancelUpload) { Text("Cancel") } }
        )
    }
}
