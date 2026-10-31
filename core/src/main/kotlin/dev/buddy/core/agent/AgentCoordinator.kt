package dev.buddy.core.agent

import dev.buddy.core.ChatMessage
import dev.buddy.core.ModelConfig
import dev.buddy.core.RunRecord
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class AgentCoordinator {
    private data class Active(val inbox: AgentInbox, val task: Deferred<AgentCheckpoint>)
    private val lock = Any()
    private val active = mutableMapOf<String, Active>()

    fun update(conversationId: String, text: String): Boolean = synchronized(lock) {
        active[conversationId]?.inbox?.offer(text) == true
    }

    fun pause(conversationId: String): Boolean = synchronized(lock) {
        active[conversationId]?.inbox?.requestPause() == true
    }

    fun stop(conversationId: String): Boolean {
        val task = synchronized(lock) { active[conversationId]?.task } ?: return false
        task.cancel()
        return true
    }

    fun isRunning(conversationId: String): Boolean = synchronized(lock) { conversationId in active }

    suspend fun run(
        runner: AgentRunner,
        request: RunRecord,
        config: ModelConfig,
        history: List<ChatMessage> = emptyList(),
        limits: AgentLimits = AgentLimits()
    ): AgentCheckpoint = coordinate(request.conversationId) { inbox ->
        runner.run(request, config, history, limits, inbox)
    }

    suspend fun resume(
        runner: AgentRunner,
        checkpoint: AgentCheckpoint,
        config: ModelConfig,
        answer: String
    ): AgentCheckpoint = coordinate(checkpoint.run.conversationId) { inbox ->
        runner.resume(checkpoint, config, answer, inbox)
    }

    private suspend fun coordinate(
        conversationId: String,
        operation: suspend (AgentInbox) -> AgentCheckpoint
    ): AgentCheckpoint = coroutineScope {
        require(conversationId.isNotBlank() && conversationId.length <= 128)
        val inbox = AgentInbox()
        // Registration happens before the child can ask a model or dispatch any tool.
        val task = async(start = CoroutineStart.LAZY) { operation(inbox) }
        synchronized(lock) {
            check(conversationId !in active) { "This conversation already has an active run" }
            active[conversationId] = Active(inbox, task)
        }
        try {
            task.start()
            task.await()
        } finally {
            synchronized(lock) {
                if (active[conversationId]?.task === task) active.remove(conversationId)
            }
        }
    }
}
