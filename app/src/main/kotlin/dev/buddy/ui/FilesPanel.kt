package dev.buddy.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.buddy.BuddyState
import dev.buddy.BuddyViewModel
import dev.buddy.storage.WorkspaceArtifact

@Composable internal fun FilesPanel(state: BuddyState, model: BuddyViewModel) {
    val context = LocalContext.current
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (state.loaded) {
                model.importDocument(uri)
            } else {
                model.fileError("Your conversation is loading. Choose the file again when it opens.")
            }
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            if (state.loaded) {
                model.importDocument(uri)
            } else {
                model.fileError("Your conversation is loading. Choose the file again when it opens.")
            }
        }
    }
    val export = rememberLauncherForActivityResult(CreateArtifactDocument()) { destination ->
        val id = exportId
        exportId = null
        if (destination != null && id != null) {
            if (state.loaded && state.artifacts.any { it.id == id }) {
                model.exportArtifact(id, destination)
            } else {
                model.fileError("No file was exported. Open its conversation, then choose Save a copy again.")
            }
        }
    }
    val busy = state.active || state.importing || state.exporting
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow {
            TextButton(onClick = {
                documents.launch(arrayOf("text/plain", "text/markdown", "application/json", "application/pdf"))
            }, enabled = !busy) { Text("Add document") }
            TextButton(onClick = {
                photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = !busy) { Text("Add photo") }
        }
        if (state.importing || state.exporting) {
            Row {
                Text(if (state.exporting) "Saving your file…" else "Updating files…", Modifier.weight(1f))
                TextButton(onClick = model::cancelFileOperation) { Text("Cancel") }
            }
        }
        if (state.artifacts.isNotEmpty()) {
            Text("Files", style = MaterialTheme.typography.titleMedium)
            Text(
                "Stored on this device. File contents are not sent to your model.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        state.artifacts.forEach { artifact ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(artifact.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${artifact.mimeType} · ${(artifact.bytes + 1023) / 1024} KiB",
                        style = MaterialTheme.typography.bodySmall
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            openArtifact(context, artifact, false, model)
                        }, enabled = !busy) { Text("Open") }
                        TextButton(onClick = {
                            openArtifact(context, artifact, true, model)
                        }, enabled = !busy) { Text("Share") }
                        TextButton(onClick = {
                            exportId = artifact.id
                            export.launch(artifact)
                        }, enabled = !busy) { Text("Save a copy") }
                        TextButton(onClick = { removingId = artifact.id }, enabled = !busy) { Text("Remove") }
                    }
                }
            }
        }
    }
    state.artifacts.firstOrNull { it.id == removingId }?.let { artifact ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text("Remove file?") },
            text = {
                Text(
                    "Remove ${artifact.displayName} from this conversation? Copies you saved or shared stay where you put them."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removingId = null
                    model.removeArtifact(artifact.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text("Keep file") } }
        )
    }
}

private fun openArtifact(context: Context, artifact: WorkspaceArtifact, share: Boolean, model: BuddyViewModel) {
    val uri = Uri.Builder().scheme("content").authority(context.packageName + ".artifacts")
        .appendPath(artifact.workspace).appendPath(artifact.id).build()
    val intent = Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        if (share) {
            type = artifact.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            setDataAndType(uri, artifact.mimeType)
        }
        clipData = ClipData.newRawUri("File", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, if (share) "Share file" else "Open file"))
    } catch (_: Exception) {
        model.fileError("No app could open this file. You can save a copy instead.")
    }
}

private class CreateArtifactDocument : ActivityResultContract<WorkspaceArtifact, Uri?>() {
    override fun createIntent(context: Context, input: WorkspaceArtifact) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.mimeType
            putExtra(Intent.EXTRA_TITLE, input.displayName)
        }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK && it.scheme == "content" }
}
