package dev.buddy.scheduling

import dev.buddy.BuddyState
import dev.buddy.core.scheduling.ScheduleDefinition
import dev.buddy.storage.ScheduleRepository
import dev.buddy.storage.ScheduleSnapshot
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SchedulePanelState(
    val loaded: Boolean = false,
    val snapshot: ScheduleSnapshot = ScheduleSnapshot(emptyList(), emptyList()),
    val busy: Boolean = false,
    val error: String? = null
)

internal class ScheduleController(
    private val repository: ScheduleRepository,
    private val coordinator: ScheduleCoordinator?,
    private val buddy: MutableStateFlow<BuddyState>,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(SchedulePanelState())
    val state = _state.asStateFlow()
    private var mutation: Job? = null
    init {
        scope.launch {
            try {
                repository.observe().collect { snapshot ->
                    _state.update { it.copy(loaded = true, snapshot = snapshot) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = "Schedules could not be opened.") }
            }
        }
    }
    private fun mutate(action: suspend (ScheduleCoordinator) -> Unit) {
        val runtime = coordinator ?: return
        if (!buddy.value.loaded || buddy.value.clearingData || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        mutation = scope.launch {
            try {
                action(runtime)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _state.update { it.copy(error = "The schedule could not be changed. Check the address and time.") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
    fun save(definition: ScheduleDefinition) = mutate { runtime ->
        val rule = repository.save(definition, Instant.now(), ZoneId.systemDefault())
        runtime.cancel(definition.id)
        runtime.enqueue(rule)
        runtime.updateReceiver()
    }
    fun enabled(id: String, enabled: Boolean) = mutate { runtime ->
        val rule = repository.enabled(id, enabled, Instant.now(), ZoneId.systemDefault())
        runtime.cancel(id)
        rule?.let { runtime.enqueue(it) }
        runtime.updateReceiver()
    }
    fun delete(id: String) = mutate { runtime ->
        repository.delete(id)
        runtime.cancel(id)
    }
    suspend fun cleared() {
        coordinator?.cancelAll()
        coordinator?.updateReceiver()
    }
    suspend fun clear() {
        mutation?.cancel()
        mutation?.join()
        coordinator?.cancelAll()
    }
}
