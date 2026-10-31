package dev.buddy.files

import android.net.Uri
import dev.buddy.BuddyState
import dev.buddy.core.Conversation
import dev.buddy.storage.ArtifactReadBudget
import dev.buddy.storage.EncryptedStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        if (!mayStart()) return
        val conversation = _state.value.conversation
        _state.update { it.copy(importing = true, error = null) }
        fileJob = scope.launch {
            try {
                val selected = reader.read(uri)
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        error = "This file could not be imported. Check its type and available space. " +
                            "Files can be up to 8 MiB; JSON up to 256 KiB."
                    )
                }
            } finally {
                _state.update { it.copy(importing = false) }
            }
        }
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

    fun fileError(message: String) {
        _state.update { it.copy(error = message) }
    }
}
