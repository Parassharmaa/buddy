package dev.buddy.core.agent

import dev.buddy.core.ChatMessage
import dev.buddy.core.RunState
import kotlinx.serialization.json.Json

internal object CheckpointRecovery {
    private val resumable = setOf(
        RunState.PAUSED,
        RunState.WAITING_FOR_USER,
        RunState.WAITING_FOR_APPROVAL,
        RunState.HANDED_TO_USER,
        RunState.INTERRUPTED
    )

    fun restore(
        checkpoint: AgentCheckpoint,
        answer: String,
        model: AgentModelIdentity,
        system: String
    ): AgentCheckpoint {
        require(checkpoint.run.state in resumable)
        require(answer.isNotBlank() && answer.length <= 8192)
        require(checkpoint.model == model) { "Confirm provider changes in a new run" }
        require(checkpoint.run.id.isNotBlank() && checkpoint.run.id.length <= 128)
        require(checkpoint.run.conversationId.isNotBlank() && checkpoint.run.conversationId.length <= 128)
        require(checkpoint.run.request.isNotBlank() && checkpoint.run.request.length <= 8192)
        require(checkpoint.modelRequests in 0..checkpoint.limits.modelRequests)
        require(checkpoint.activeMillis in 0..660_000 && checkpoint.confirmedTokens in 0..60L * Int.MAX_VALUE)
        require(checkpoint.estimatedTokens in 0..256L * 1024 * 1024)
        require(checkpoint.missingUsageRequests in 0..checkpoint.modelRequests)
        require(checkpoint.retries in 0..3 && checkpoint.retryWaitMillis in 0..30_000)
        require(checkpoint.compactedObservations in 0..1024)
        require(
            checkpoint.run.evidenceIds.size <= 8192 && checkpoint.run.evidenceIds.all {
                it.isNotBlank() && it.length <= 128
            }
        )
        require(
            checkpoint.userUpdates.size <= 1024 && checkpoint.userUpdates.all {
                it.isNotBlank() && it.length <= 8192
            }
        )
        require(checkpoint.messages.size in 2..4096)
        require(Json.encodeToString(AgentCheckpoint.serializer(), checkpoint).toByteArray().size <= 4 * 1024 * 1024)
        require(checkpoint.messages.first() == ChatMessage("system", system))
        val instructions = checkpoint.messages.filter { it.role == "user" }.map { it.content }
        require(
            instructions.takeLast(checkpoint.userUpdates.size + 1) ==
                listOf(checkpoint.run.request) + checkpoint.userUpdates
        )
        val pending = linkedSetOf<String>()
        val seen = hashSetOf<String>()
        checkpoint.messages.drop(1).forEach { message ->
            when (message.role) {
                "assistant" -> {
                    require(pending.isEmpty() && message.toolCallId == null)
                    message.toolCalls.orEmpty().forEach {
                        require(it.id.isNotBlank() && it.id.length <= 128 && seen.add(it.id))
                        pending += it.id
                    }
                }

                "tool" -> require(message.toolCalls.isNullOrEmpty() && pending.remove(message.toolCallId))

                "user" -> require(pending.isEmpty() && message.toolCalls.isNullOrEmpty() && message.toolCallId == null)

                else -> error("Unexpected checkpoint message role")
            }
        }
        require(pending.isEmpty() || checkpoint.run.state == RunState.INTERRUPTED)
        val interrupted = pending.map {
            ChatMessage(
                "tool",
                """{"status":"interrupted","reason":"Inspect the outcome before continuing; this call was not replayed"}""",
                toolCallId = it
            )
        }
        return checkpoint.copy(
            run = checkpoint.run.copy(state = RunState.PREPARING),
            messages = checkpoint.messages + interrupted + ChatMessage("user", answer),
            pendingAction = null,
            userUpdates = checkpoint.userUpdates + answer
        )
    }
}
