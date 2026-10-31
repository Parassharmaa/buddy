package dev.buddy.core.agent

import dev.buddy.core.ActionProposal
import dev.buddy.core.Bounds
import dev.buddy.core.ChatMessage
import dev.buddy.core.Effect
import dev.buddy.core.ExecutionResult
import dev.buddy.core.FunctionCall
import dev.buddy.core.ModelConfig
import dev.buddy.core.ModelFailure
import dev.buddy.core.ModelReply
import dev.buddy.core.Observation
import dev.buddy.core.ObservationPrivacyGate
import dev.buddy.core.RunRecord
import dev.buddy.core.RunState
import dev.buddy.core.ToolCall
import dev.buddy.core.UiElement
import dev.buddy.core.model.ChatModel
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentRunnerTest {
    private val config = ModelConfig("https://example.com/v1", "fixture", "")
    private val request = RunRecord("r", "c", "Inspect the controlled fixture", createdAt = 0)
    private val observation = Observation(
        "o1", "r", 1, 0, "fixture", 100, 200,
        listOf(UiElement("editor", "Public fixture", "fixture", Bounds(0, 0, 50, 20), editable = true)), "fp"
    )
    private val action = ActionProposal("a1", "r", "o1", 1, "fixture", Effect.DRAFT, "editor", "Hello", "draft", "fp")
    private class Model(val respond: suspend (Int) -> ModelReply) : ChatModel {
        var calls = 0
        val requests = mutableListOf<List<ChatMessage>>()
        override suspend fun complete(
            config: ModelConfig,
            messages: List<ChatMessage>,
            tools: List<JsonObject>
        ): ModelReply {
            requests += messages.toList()
            return respond(calls++)
        }
    }
    private fun calls(vararg names: String): ModelReply = ModelReply(
        ChatMessage(
            "assistant",
            toolCalls = names.map {
                ToolCall(UUID.randomUUID().toString(), function = FunctionCall(it, "{}"))
            }
        ),
        10,
        "tool_calls"
    )
    private fun finish(ids: String = "o1") = ModelReply(
        ChatMessage(
            "assistant",
            toolCalls = listOf(
                ToolCall(
                    UUID.randomUUID().toString(),
                    function = FunctionCall(
                        "end_task",
                        """{"outcome":"Fixture completed","evidence_ids":["$ids"]}"""
                    )
                )
            )
        ),
        10,
        "tool_calls"
    )
    private fun tool(
        name: String = "observe",
        effect: Effect = Effect.READ,
        prepare: suspend () -> AgentInvocation = { AgentInvocation.Read { observation } }
    ) = object : AgentTool {
        override val name = name
        override val effect = effect
        override val description = "Controlled fixture tool"
        override val parameters = Json.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"properties":{}}"""
        ).jsonObject
        override fun validate(arguments: JsonObject) {
            require(arguments.isEmpty())
        }
        override suspend fun prepare(arguments: JsonObject) = prepare()
    }
    private fun runner(
        model: Model,
        tools: List<AgentTool> = listOf(tool()),
        saved: MutableList<AgentCheckpoint> = mutableListOf(),
        verify: suspend (String, List<String>) -> Boolean = { _, _ -> true },
        retained: MutableList<Observation> = mutableListOf(),
        clock: () -> Long = { 0 }
    ) = AgentRunner(
        model,
        tools,
        ObservationPrivacyGate(emptySet()),
        verify,
        { saved += it },
        { retained += it },
        now = clock
    )

    @Test fun completionRequiresObservedEvidenceAndHostVerification() = runTest {
        val model = Model { if (it == 0) calls("observe") else finish() }
        var verified = false
        val result = runner(model, verify = { _, ids ->
            verified = ids == listOf("o1")
            verified
        }).run(request, config)
        assertEquals(RunState.DONE, result.run.state)
        assertTrue(verified)
        assertEquals(2, result.modelRequests)
        assertTrue(model.requests.last().any { it.role == "tool" && it.content.orEmpty().contains("untrusted") })
    }

    @Test fun inventedEvidenceNeverReachesTheVerifierOrDone() = runTest {
        val model = Model { finish("invented") }
        var verified = false
        val result = runner(model, verify = { _, _ ->
            verified = true
            true
        }).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertEquals(3, model.calls)
        assertFalse(verified)
    }

    @Test fun aPrivateObservationNeverEntersModelOrCheckpointMessages() = runTest {
        val saved = mutableListOf<AgentCheckpoint>()
        val secret = "NEVER_FORWARD_PRIVATE_FIXTURE"
        val privateObservation = observation.copy(
            protected = true,
            nodes = listOf(observation.nodes.single().copy(label = secret))
        )
        val model = Model { calls("observe") }
        val result = runner(
            model,
            listOf(
                tool {
                    AgentInvocation.Read { privateObservation }
                }
            ),
            saved
        ).run(request, config)
        assertEquals(RunState.WAITING_FOR_USER, result.run.state)
        assertEquals(1, model.calls)
        assertTrue(saved.flatMap { it.messages }.none { it.content.orEmpty().contains(secret) })
        assertTrue(model.requests.flatten().none { it.content.orEmpty().contains(secret) })
        assertTrue(result.run.evidenceIds.isEmpty())
    }

    @Test fun anInvalidBatchDoesNotEvenPrepareItsValidPrefix() = runTest {
        var prepared = 0
        val model = Model { calls("observe", "unregistered") }
        val read = tool {
            prepared++
            AgentInvocation.Read { observation }
        }
        val result = runner(model, listOf(read)).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertEquals(0, prepared)
    }

    @Test fun multipleWritesAreRejectedBeforeDispatch() = runTest {
        var writes = 0
        val write = tool("draft", Effect.DRAFT) {
            AgentInvocation.Action(action) {
                writes++
                ExecutionResult.Verified(listOf("e1"))
            }
        }
        val result = runner(Model { calls("draft", "draft") }, listOf(write)).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertEquals(0, writes)
    }

    @Test fun aConsequentialActionPausesWithoutHostConsent() = runTest {
        var writes = 0
        val send = action.copy(effect = Effect.SEND, target = "Alice", destination = "fixture inbox")
        val tool = tool("send", Effect.SEND) {
            AgentInvocation.Action(send) {
                writes++
                ExecutionResult.Verified(listOf("e1"))
            }
        }
        val result = runner(Model { calls("send") }, listOf(tool)).run(request, config)
        assertEquals(RunState.WAITING_FOR_APPROVAL, result.run.state)
        assertEquals(send, result.pendingAction)
        assertEquals(0, writes)
    }

    @Test fun anUncertainEffectStopsWithoutAnotherModelRequestOrWrite() = runTest {
        var writes = 0
        val model = Model { calls("draft") }
        val tool =
            tool("draft", Effect.DRAFT) {
                AgentInvocation.Action(action) {
                    writes++
                    ExecutionResult.Uncertain
                }
            }
        val result = runner(model, listOf(tool)).run(request, config)
        assertEquals(RunState.UNKNOWN, result.run.state)
        assertEquals(1, writes)
        assertEquals(1, model.calls)
    }

    @Test fun cancellationBalancesThePendingCallAndDoesNotClaimAnInflightWriteWasUnexecuted() = runTest {
        val started = CompletableDeferred<Unit>()
        val saved = mutableListOf<AgentCheckpoint>()
        val model = Model { calls("draft") }
        val tool = tool("draft", Effect.DRAFT) {
            AgentInvocation.Action(action) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch { runner(model, listOf(tool), saved).run(request, config) }
        started.await()
        job.cancelAndJoin()
        assertEquals(RunState.CANCELLED, saved.last().run.state)
        assertTrue(saved.last().messages.last().content.orEmpty().contains("uncertain"))
        assertEquals(1, model.calls)
    }

    @Test fun cancellationAbortsTheModelBeforeAnyToolDispatch() = runTest {
        val started = CompletableDeferred<Unit>()
        val saved = mutableListOf<AgentCheckpoint>()
        val model = Model {
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch { runner(model, saved = saved).run(request, config) }
        started.await()
        job.cancelAndJoin()
        assertEquals(RunState.CANCELLED, saved.last().run.state)
        assertEquals(1, model.calls)
    }

    @Test fun modelRequestAndActiveTimeBudgetsStopBeforeFurtherDispatch() = runTest {
        val requestLimited = runner(
            Model {
                calls("observe")
            }
        ).run(request, config, limits = AgentLimits(modelRequests = 2))
        assertEquals(RunState.PARTIAL, requestLimited.run.state)
        assertEquals(2, requestLimited.modelRequests)
        val model = Model {
            delay(20)
            calls("observe")
        }
        val timeLimited = runner(model, clock = { testScheduler.currentTime }).run(
            request,
            config,
            limits = AgentLimits(activeMillis = 10)
        )
        assertEquals(RunState.PARTIAL, timeLimited.run.state)
        assertTrue(timeLimited.run.evidenceIds.isEmpty())
    }

    @Test fun unchangedReadsStopAfterTheBoundedNoProgressThreshold() = runTest {
        val model = Model { calls("observe") }
        val result = runner(model).run(request, config)
        assertEquals(RunState.PARTIAL, result.run.state)
        assertEquals(5, model.calls)
    }

    @Test fun transientModelRetriesHaveASharedBudgetAndDoNotInvokeTools() = runTest {
        val model = Model { throw ModelFailure("connection", "PRIVATE_FAILURE_MESSAGE") }
        val result = runner(model, clock = { testScheduler.currentTime }).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertEquals(4, model.calls)
        assertEquals(3, result.retries)
        assertEquals(7_000L, result.retryWaitMillis)
        assertFalse(result.toString().contains("PRIVATE_FAILURE_MESSAGE"))
    }

    @Test fun oversizedContextStopsWithoutCallingTheModel() = runTest {
        val model = Model { calls("observe") }
        val result = runner(model).run(request, config, limits = AgentLimits(contextBytes = 1024))
        assertEquals(RunState.PARTIAL, result.run.state)
        assertEquals(0, model.calls)
    }

    @Test fun crossRunObservationsAndMisdeclaredWriteToolsCannotDispatch() = runTest {
        val wrong = tool { AgentInvocation.Read { observation.copy(runId = "another-run") } }
        val result = runner(Model { calls("observe") }, listOf(wrong)).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertTrue(result.run.evidenceIds.isEmpty())
        var writes = 0
        val misdeclared = tool {
            AgentInvocation.Action(action) {
                writes++
                ExecutionResult.Verified(listOf("e1"))
            }
        }
        val blocked = runner(Model { calls("observe") }, listOf(misdeclared)).run(request, config)
        assertEquals(RunState.BLOCKED, blocked.run.state)
        assertEquals(0, writes)
    }

    @Test fun repeatedToolCallIdsCannotDispatchAgain() = runTest {
        var writes = 0
        val response = calls("draft")
        val write = tool("draft", Effect.DRAFT) {
            AgentInvocation.Action(action) {
                writes++
                ExecutionResult.Verified(listOf("e1"))
            }
        }
        val model = Model { response }
        val result = runner(model, listOf(write)).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertEquals(2, model.calls)
        assertEquals(1, writes)
    }

    @Test fun sourceIdsCannotBeReboundToDifferentObservationContent() = runTest {
        var reads = 0
        val retained = mutableListOf<Observation>()
        val read = tool {
            AgentInvocation.Read {
                if (reads++ == 0) observation else observation.copy(fingerprint = "changed")
            }
        }
        val result = runner(Model { calls("observe") }, listOf(read), retained = retained).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertEquals(listOf(observation), retained)
        assertEquals(listOf("o1"), result.run.evidenceIds)
        assertTrue(result.messages.none { it.role == "tool" && it.content.orEmpty().contains("changed") })
    }

    @Test fun knownEvidenceStillRequiresTheHostToVerifyCompletion() = runTest {
        var checks = 0
        val model = Model { if (it == 0) calls("observe") else finish() }
        val result = runner(model, verify = { _, _ ->
            checks++
            false
        }).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertEquals(3, checks)
    }

    @Test fun contextCompactionKeepsTheGoalAndEvidenceWhileBoundingModelRequests() = runTest {
        var reads = 0
        val retained = mutableListOf<Observation>()
        val read = tool {
            AgentInvocation.Read {
                reads++
                observation.copy(
                    id = "o$reads",
                    fingerprint = "fp$reads",
                    nodes = listOf(observation.nodes.single().copy(label = "x".repeat(1000)))
                )
            }
        }
        val model = Model { if (it < 6) calls("observe") else finish("o6") }
        val result = runner(model, listOf(read), retained = retained).run(
            request,
            config,
            limits = AgentLimits(modelRequests = 8, contextBytes = 8192)
        )
        assertEquals(RunState.DONE, result.run.state)
        assertEquals(6, retained.size)
        assertTrue(result.compactedObservations > 0)
        assertTrue(
            model.requests.all {
                it.any { message -> message.role == "user" && message.content == request.request }
            }
        )
        assertEquals((1..6).map { "o$it" }, result.run.evidenceIds)
    }

    @Test fun checkpointsKeepModelAndBudgetsWithoutRetainingTheApiKey() = runTest {
        val secret = "PRIVATE_FIXTURE_API_KEY"
        val limits = AgentLimits(modelRequests = 2, activeMillis = 1000, tokens = 50_000)
        val result = runner(Model { finish("invented") }).run(request, config.copy(apiKey = secret), limits = limits)
        val encoded = Json.encodeToString(AgentCheckpoint.serializer(), result)
        assertFalse(encoded.contains(secret))
        assertEquals(AgentModelIdentity(config.baseUrl, config.model, false), result.model)
        assertEquals(limits, result.limits)
    }
}
