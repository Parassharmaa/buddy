package dev.buddy.core.agent

import dev.buddy.core.ActionProposal
import dev.buddy.core.Approval
import dev.buddy.core.ChatMessage
import dev.buddy.core.Effect
import dev.buddy.core.ExecutionResult
import dev.buddy.core.Observation
import dev.buddy.core.RunRecord
import dev.buddy.core.RunState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

data class AgentLimits(
    val modelRequests: Int = 60,
    val activeMillis: Long = 10 * 60 * 1000,
    val tokens: Long? = null,
    val contextBytes: Int = 65_536
) {
    init {
        require(modelRequests in 1..60 && activeMillis in 1..600_000)
        require(tokens == null || tokens > 0)
        require(contextBytes in 1024..1_048_576)
    }
}

@Serializable
data class AgentCheckpoint(
    val run: RunRecord,
    val messages: List<ChatMessage>,
    val modelRequests: Int = 0,
    val confirmedTokens: Long = 0,
    val estimatedTokens: Long = 0,
    val missingUsageRequests: Int = 0,
    val activeMillis: Long = 0,
    val retries: Int = 0,
    val retryWaitMillis: Long = 0,
    val pendingAction: ActionProposal? = null
) {
    override fun toString(): String = "AgentCheckpoint(redacted)"
}

interface AgentTool {
    val name: String
    val description: String
    val parameters: JsonObject
    val effect: Effect
    val timeoutMillis: Long get() = 5_000

    // Both validation and preparation are read-only. Write callbacks must use ActionExecutor.
    fun validate(arguments: JsonObject)
    suspend fun prepare(arguments: JsonObject): AgentInvocation
}

sealed interface AgentInvocation {
    class Read(val operation: suspend () -> Observation) : AgentInvocation
    class Action(val proposal: ActionProposal, val operation: suspend (Approval?) -> ExecutionResult) : AgentInvocation
    data class Pause(val state: RunState, val reason: String) : AgentInvocation
}
