package dev.buddy

import android.os.SystemClock
import dev.buddy.core.ChatMessage
import dev.buddy.core.DispatchGate
import dev.buddy.core.Effect
import dev.buddy.core.FunctionCall
import dev.buddy.core.ModelConfig
import dev.buddy.core.ModelReply
import dev.buddy.core.Observation
import dev.buddy.core.ObservationPrivacyGate
import dev.buddy.core.RunRecord
import dev.buddy.core.RunState
import dev.buddy.core.ToolCall
import dev.buddy.core.agent.AgentCheckpoint
import dev.buddy.core.agent.AgentInvocation
import dev.buddy.core.agent.AgentLimits
import dev.buddy.core.agent.AgentRunner
import dev.buddy.core.agent.AgentTool
import dev.buddy.core.model.ChatModel
import dev.buddy.phone.FixtureWorkspace
import dev.buddy.storage.EncryptedStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

// Only the disposable qualification fixture gets these tools; live chat never registers them.
internal object FixtureAgentProbe {
    suspend fun verify(workspace: FixtureWorkspace, store: EncryptedStore, runId: String) {
        val text = "Agent fixture · こんにちは · café"
        var observed: Observation? = null
        val emptySchema = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"properties":{}}"""
        ).jsonObject
        fun tool(name: String, effect: Effect, prepare: suspend () -> AgentInvocation) = object : AgentTool {
            override val name = name
            override val description = "Controlled fixture qualification"
            override val effect = effect
            override val parameters = emptySchema
            override fun validate(arguments: JsonObject) = require(arguments.isEmpty())
            override suspend fun prepare(arguments: JsonObject) = prepare()
        }
        val tools = listOf(
            tool("fixture_observe", Effect.READ) {
                AgentInvocation.Read { workspace.observe().also { observed = it } }
            },
            tool("fixture_draft", Effect.DRAFT) {
                val draft = workspace.prepareDraft(requireNotNull(observed), text)
                AgentInvocation.Action(draft.proposal) { dispatch ->
                    workspace.execute(draft, dispatch.approval) {
                        DispatchGate(
                            !dispatch.isCurrent(),
                            false,
                            false,
                            true,
                            true,
                            Long.MAX_VALUE,
                            Long.MAX_VALUE,
                            false
                        )
                    }
                }
            }
        )
        var turn = 0
        val model = object : ChatModel {
            override suspend fun complete(
                config: ModelConfig,
                messages: List<ChatMessage>,
                tools: List<JsonObject>
            ): ModelReply {
                val name = when (turn++) {
                    0 -> "fixture_observe"
                    1 -> "fixture_draft"
                    else -> "end_task"
                }
                val arguments = if (name == "end_task") {
                    val result = Json.parseToJsonElement(requireNotNull(messages.last().content)).jsonObject
                    assertEquals("verified", result.getValue("status").jsonPrimitive.content)
                    buildJsonObject {
                        put("outcome", "The controlled fixture contains the requested draft")
                        put("evidence_ids", result.getValue("evidence_ids") as JsonArray)
                    }.toString()
                } else {
                    "{}"
                }
                return ModelReply(
                    ChatMessage(
                        "assistant",
                        toolCalls = listOf(ToolCall("fixture-call-$turn", function = FunctionCall(name, arguments)))
                    ),
                    10,
                    "tool_calls"
                )
            }
        }
        var verificationCount = 0
        val runner = AgentRunner(
            model,
            tools,
            ObservationPrivacyGate(emptySet()),
            verifyCompletion = { _, ids ->
                verificationCount++
                ids.all { store.get("evidence:$it") != null } &&
                    workspace.observe().nodes.any { it.editable && it.label == text }
            },
            persist = { store.put("agent:$runId", "agent-checkpoint", Json.encodeToString(it)) },
            retainObservation = { store.put("evidence:${it.id}", "evidence", Json.encodeToString(it)) },
            now = SystemClock::elapsedRealtime
        )
        val result = runner.run(
            RunRecord(runId, "fixture", "Set the fixture draft to $text", createdAt = System.currentTimeMillis()),
            ModelConfig("https://example.com/v1", "deterministic-fixture", ""),
            limits = AgentLimits(modelRequests = 3, activeMillis = 10_000)
        )
        assertEquals("Agent stopped: ${result.run.summary}", RunState.DONE, result.run.state)
        assertEquals(1, verificationCount)
        assertEquals(3, result.modelRequests)
        assertTrue(result.run.evidenceIds.isNotEmpty())
        val restored = Json.decodeFromString<AgentCheckpoint>(requireNotNull(store.get("agent:$runId")))
        assertEquals(RunState.DONE, restored.run.state)
        assertEquals(result.run.evidenceIds, restored.run.evidenceIds)
    }
}
