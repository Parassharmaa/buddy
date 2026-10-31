package dev.buddy.ui

import dev.buddy.BuddyState
import dev.buddy.browser.WebReadResult
import dev.buddy.core.ChatSource
import dev.buddy.core.ChatSourceKind
import dev.buddy.core.ModelConfig
import dev.buddy.core.SourceContext
import dev.buddy.storage.ArtifactReadBudget
import dev.buddy.storage.EncryptedStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PendingSource(
    val workspace: String,
    val source: ChatSource,
    val provider: String,
    val model: String
) {
    override fun toString(): String = "PendingSource(redacted)"
}
internal data class SourcePanelState(val preparing: Boolean = false, val pending: PendingSource? = null)

internal class SourceController(
    private val store: EncryptedStore,
    private val scope: CoroutineScope,
    private val buddy: MutableStateFlow<BuddyState>,
    private val config: () -> ModelConfig?,
    private val readPage: suspend () -> WebReadResult
) {
    private val _state = MutableStateFlow(SourcePanelState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var epoch = 0

    private fun available(): Boolean = buddy.value.let {
        it.loaded && !it.active && !it.importing && !it.exporting
    }
    private fun error(message: String) {
        buddy.update { it.copy(error = message) }
    }
    private fun prepare(read: suspend (ModelConfig) -> ChatSource) {
        if (!available() || job?.isActive == true) return
        val selected = config() ?: run {
            error("Choose a model in Settings before sharing source text.")
            return
        }
        val workspace = buddy.value.conversation.id
        val ticket = ++epoch
        _state.value = SourcePanelState(preparing = true)
        job = scope.launch {
            try {
                val source = read(selected)
                if (ticket != epoch) return@launch
                if (buddy.value.conversation.id != workspace || !available()) {
                    _state.value = SourcePanelState()
                    return@launch
                }
                _state.value = SourcePanelState(
                    pending = PendingSource(workspace, source, selected.baseUrl, selected.model)
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ticket == epoch) {
                    _state.value = SourcePanelState()
                    error("This source could not be prepared. Use a readable text document or a public page.")
                }
            }
        }
    }
    fun file(id: String) {
        val workspace = buddy.value.conversation.id
        val file = buddy.value.artifacts.firstOrNull { it.id == id } ?: return
        if (file.mimeType !in setOf("text/plain", "text/markdown", "application/json")) {
            error("Only text documents can be sent as model context in this build.")
            return
        }
        prepare { selected ->
            val bytes = store.artifacts.read(workspace, id, ArtifactReadBudget(256 * 1024))
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } finally {
                bytes.fill(0)
            }
            source(id, ChatSourceKind.FILE, file.displayName, text, false, selected)
        }
    }
    fun page() = prepare { selected ->
        val result = readPage()
        require(result is WebReadResult.Allowed)
        val snapshot = result.snapshot
        source(
            UUID.randomUUID().toString(),
            ChatSourceKind.PAGE,
            snapshot.sourceLabel,
            snapshot.text,
            snapshot.truncated,
            selected
        )
    }
    private fun source(
        id: String,
        kind: ChatSourceKind,
        label: String,
        text: String,
        truncated: Boolean,
        selected: ModelConfig
    ): ChatSource {
        val limit =
            SourceContext.MAX_CHARACTERS - buddy.value.draftSources.filterNot { it.id == id }.sumOf { it.text.length }
        require(limit > 0)
        var clipped = text.take(limit)
        if (clipped.lastOrNull()?.isHighSurrogate() == true) clipped = clipped.dropLast(1)
        return ChatSource(
            id,
            kind,
            label,
            clipped,
            truncated || clipped.length < text.length,
            SourceContext.destination(selected)
        )
    }
    fun confirm(): Boolean {
        val pending = _state.value.pending ?: return false
        if (!available() || pending.workspace != buddy.value.conversation.id) {
            cancel()
            return false
        }
        if (pending.source.kind == ChatSourceKind.FILE && buddy.value.artifacts.none { it.id == pending.source.id }) {
            cancel()
            error("That file was removed. Choose a source again.")
            return false
        }
        val selected = config() ?: return false
        val sources = buddy.value.draftSources.filterNot { it.id == pending.source.id } + pending.source
        if (runCatching { SourceContext.validate(sources, selected) }.isFailure) {
            cancel()
            error("The model changed or the source limit was reached. Choose the source again.")
            return false
        }
        buddy.update { it.copy(draftSources = sources) }
        _state.value = SourcePanelState()
        return true
    }
    fun remove(id: String) {
        buddy.update { it.copy(draftSources = it.draftSources.filterNot { source -> source.id == id }) }
        if (_state.value.pending?.source?.id == id) cancel()
    }
    fun cancel(clearDraft: Boolean = false) {
        epoch++
        job?.cancel()
        job = null
        _state.value = SourcePanelState()
        if (clearDraft) buddy.update { it.copy(draftSources = emptyList()) }
    }
}
