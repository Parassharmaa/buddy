package dev.buddy.ui

import dev.buddy.BuddyState
import dev.buddy.core.MemoryPolicy
import dev.buddy.core.SavedMemory
import dev.buddy.storage.MemoryRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class MemoryEditor(
    val id: String = UUID.randomUUID().toString(),
    val memoryId: String? = null,
    val sourceMessageId: String,
    val fact: String,
    val workspace: String? = null
) {
    override fun toString(): String = "MemoryEditor(redacted)"
}
internal data class MemoryPanelState(
    val loaded: Boolean = false,
    val enabled: Boolean = false,
    val memories: List<SavedMemory> = emptyList(),
    val busy: Boolean = false,
    val editor: MemoryEditor? = null,
    val error: String? = null
)

internal class MemoryController(
    private val repository: MemoryRepository,
    private val buddy: MutableStateFlow<BuddyState>,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(MemoryPanelState())
    val state = _state.asStateFlow()
    private var mutation: Job? = null
    private var observer: Job? = null
    init {
        observe()
    }
    private fun observe() {
        observer?.cancel()
        observer = scope.launch {
            try {
                repository.observe().collect { snapshot ->
                    _state.update {
                        it.copy(loaded = true, enabled = snapshot.enabled, memories = snapshot.memories, error = null)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        loaded = true,
                        enabled = false,
                        memories = emptyList(),
                        error = "Memories could not be opened."
                    )
                }
            }
        }
    }
    private fun available() = buddy.value.loaded && !buddy.value.active && !_state.value.busy && _state.value.loaded
    fun remember(index: Int) {
        if (!available()) return
        val conversation = buddy.value.conversation
        val message = conversation.messages.getOrNull(index) ?: return
        val content = message.content ?: return
        if (message.role != "user" || content.isBlank()) return
        _state.update {
            it.copy(
                editor = MemoryEditor(
                    sourceMessageId = MemoryPolicy.sourceMessage(conversation.id, index),
                    fact = content.take(MemoryPolicy.MAX_CHARACTERS),
                    workspace = conversation.id
                ),
                error = null
            )
        }
    }
    fun edit(id: String) {
        if (!available()) return
        val memory = _state.value.memories.firstOrNull { it.id == id } ?: return
        _state.update {
            it.copy(editor = MemoryEditor(memoryId = id, sourceMessageId = memory.sourceMessageId, fact = memory.fact))
        }
    }
    fun dismissEditor() {
        _state.update { it.copy(editor = null) }
    }
    private fun mutate(operation: suspend () -> Unit) {
        if (!available()) return
        _state.update { it.copy(busy = true, error = null) }
        mutation = scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update {
                    it.copy(error = "This memory could not be changed. Try again or delete saved memories.")
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
    fun save(value: String) {
        val editor = _state.value.editor ?: return
        val fact = runCatching { MemoryPolicy.fact(value) }.getOrElse {
            _state.update {
                it.copy(error = "Use one short preference, without passwords, codes, credentials or card details.")
            }
            return
        }
        if (editor.workspace != null && editor.workspace != buddy.value.conversation.id) {
            dismissEditor()
            return
        }
        mutate {
            if (editor.memoryId == null) {
                repository.save(fact, editor.sourceMessageId, enable = true)
            } else {
                repository.edit(editor.memoryId, fact)
            }
            _state.update { it.copy(editor = null) }
        }
    }
    fun enabled(value: Boolean) = mutate { repository.setEnabled(value) }
    fun delete(id: String) = mutate { repository.delete(id) }
    fun clear() = mutate {
        repository.clear()
        _state.update { it.copy(memories = emptyList(), enabled = false, editor = null) }
        observe()
    }
    fun cancel(): Job? {
        val pending = mutation
        pending?.cancel()
        mutation = null
        _state.update { it.copy(editor = null) }
        return pending
    }
}
