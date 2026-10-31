package dev.buddy.files

import android.net.Uri
import androidx.core.net.toUri
import dev.buddy.BuddyState
import dev.buddy.browser.DownloadedFile
import dev.buddy.core.Conversation
import dev.buddy.core.ConversationExports
import dev.buddy.core.ResponseFiles
import dev.buddy.storage.ArtifactReadBudget
import dev.buddy.storage.EncryptedStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class FileWorkspaceController(
    private val store: EncryptedStore,
    private val documents: DocumentAccess?,
    private val scope: CoroutineScope,
    private val _state: MutableStateFlow<BuddyState>,
    private val persistence: Mutex
) {
    private var fileJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun cancel(): Job? = fileJob.also { it?.cancel() }

    private fun mayStart() = _state.value.loaded && !_state.value.active &&
        !_state.value.importing && !_state.value.exporting && fileJob?.isActive != true

    fun open(workspace: String) {
        cancel()
        fileJob = scope.launch {
            try {
                val files = store.artifacts.list(workspace)
                currentCoroutineContext().ensureActive()
                _state.update { if (it.conversation.id == workspace) it.copy(artifacts = files) else it }
            } catch (
                cancelled: CancellationException
            ) {
                throw cancelled
            } catch (
                _: Exception
            ) {
                fileError("Files could not be opened. Try opening the conversation again.")
            }
        }
    }

    fun importDocument(uri: Uri) {
        val reader = documents ?: return
        importSelected { uri to reader.read(uri) }
    }

    fun importDownload(read: suspend () -> DownloadedFile): Boolean = importSelected {
        val file = read()
        file.origin.toUri() to SelectedDocument(file.name, file.mimeType, file.bytes)
    }

    fun saveResponse(expectedWorkspace: String, index: Int): Boolean {
        if (!mayStart()) return false
        val conversation = _state.value.conversation
        val message = conversation.messages.getOrNull(index)
        if (conversation.id != expectedWorkspace || message?.role != "assistant" || message.content.isNullOrBlank()) {
            fileError("That response is no longer available. Open its conversation again.")
            return false
        }
        val content = requireNotNull(message.content)
        return importSelected(
            failureMessage = "The response could not be saved. Check available space and the 8 MiB text limit."
        ) {
            val context = currentCoroutineContext()
            val bytes = ResponseFiles.encode(content) { context.isActive }
            "content://dev.buddy.generated/response/${conversation.id}/$index".toUri() to
                SelectedDocument("Buddy response.txt", "text/plain", bytes)
        }
    }

    private fun importSelected(
        failureMessage: String = "This file could not be imported. Check its type and available space. " +
            "Files can be up to 8 MiB; JSON up to 256 KiB.",
        read: suspend () -> Pair<Uri, SelectedDocument>
    ): Boolean {
        if (!mayStart()) return false
        val conversation = _state.value.conversation
        _state.update { it.copy(importing = true, error = null) }
        fileJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val (uri, selected) = read()
                    try {
                        val saved = conversation.copy(
                            title = if (conversation.messages.isEmpty()) "Files" else conversation.title
                        )
                        persistence.withLock {
                            store.put("conversation:${saved.id}", "conversation", json.encodeToString(saved))
                        }
                        store.artifacts.importFile(saved.id, selected.name, selected.mimeType, uri, selected.bytes)
                        val files = store.artifacts.list(saved.id)
                        currentCoroutineContext().ensureActive()
                        _state.update {
                            if (it.conversation.id ==
                                saved.id
                            ) {
                                it.copy(conversation = saved, artifacts = files)
                            } else {
                                it
                            }
                        }
                    } finally {
                        selected.bytes.fill(0)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        error = failureMessage
                    )
                }
            } finally {
                _state.update { it.copy(importing = false) }
            }
        }
        return true
    }

    fun removeArtifact(id: String) {
        if (!mayStart()) return
        val workspace = _state.value.conversation.id
        if (_state.value.artifacts.none { it.id == id }) return
        _state.update { it.copy(importing = true) }
        fileJob = scope.launch {
            try {
                store.artifacts.delete(workspace, id)
                val files = store.artifacts.list(workspace)
                _state.update { if (it.conversation.id == workspace) it.copy(artifacts = files) else it }
            } catch (
                cancelled: CancellationException
            ) {
                throw cancelled
            } catch (
                _: Exception
            ) {
                fileError("This file could not be removed. Try again.")
            } finally {
                _state.update { it.copy(importing = false) }
            }
        }
    }

    fun exportArtifact(id: String, destination: Uri) {
        val writer = documents ?: return
        if (!mayStart()) return
        val workspace = _state.value.conversation.id
        if (_state.value.artifacts.none { it.id == id }) return
        _state.update { it.copy(exporting = true, error = null) }
        fileJob = scope.launch {
            try {
                val bytes = store.artifacts.read(workspace, id, ArtifactReadBudget())
                try {
                    writer.write(destination, bytes)
                } finally {
                    bytes.fill(0)
                }
            } catch (cancelled: CancellationException) {
                fileError("Export interrupted. The destination may contain a partial file.")
                throw cancelled
            } catch (_: Exception) {
                fileError("Export failed. The destination may contain a partial file; check it before trying again.")
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun exportConversation(expectedId: String, destination: Uri) {
        val writer = documents ?: return
        if (!mayStart()) return
        val conversation = _state.value.conversation
        if (conversation.id != expectedId) {
            fileError("Open the original conversation and choose Save conversation again.")
            return
        }
        _state.update { it.copy(exporting = true, error = null) }
        fileJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    val bytes = ConversationExports.encode(conversation) { context.isActive }
                    try {
                        writer.write(destination, bytes)
                    } finally {
                        bytes.fill(0)
                    }
                }
            } catch (cancelled: CancellationException) {
                fileError("Export interrupted. The destination may contain a partial file.")
                throw cancelled
            } catch (_: Exception) {
                fileError(
                    "The conversation could not be saved. Exports are limited to 8 MiB; check the destination before trying again."
                )
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun fileError(message: String) {
        _state.update { it.copy(error = message) }
    }
}
