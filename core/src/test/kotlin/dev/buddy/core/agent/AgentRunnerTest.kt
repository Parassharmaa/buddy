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
import kotlinx.coroutines.async
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
        { verify(it.claimedOutcome, it.evidenceIds) },
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

    @Test fun anUpdateCancelsOnlyThePendingModelRequestAndPreservesTheGoal() = runTest {
        val inbox = AgentInbox()
        val started = CompletableDeferred<Unit>()
        var requestCancelled = false
        val model = Model {
            when (it) {
                0 -> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        requestCancelled = true
                    }
                }

                1 -> calls("observe")

                else -> finish()
            }
        }
        val run = async { runner(model).run(request, config, inbox = inbox) }
        started.await()
        assertTrue(inbox.offer("Use the latest user clarification"))
        val result = run.await()
        assertTrue(requestCancelled)
        assertEquals(RunState.DONE, result.run.state)
        assertEquals(3, result.modelRequests)
        assertEquals(
            listOf(request.request, "Use the latest user clarification"),
            model.requests[1].filter { it.role == "user" }.map { it.content }
        )
        assertFalse(inbox.offer("This belongs to the next run"))
    }

    @Test fun inputDuringReadOnlyPreparationPreventsTheOldWrite() = runTest {
        val inbox = AgentInbox()
        val prepared = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val write = tool("draft", Effect.DRAFT) {
            prepared.complete(Unit)
            release.await()
            AgentInvocation.Action(action) {
                writes++
                ExecutionResult.Verified(listOf("e1"))
            }
        }
        val model = Model {
            if (it ==
                0
            ) {
                calls("draft")
            } else {
                ModelReply(ChatMessage("assistant", "Reading the new instruction"), 10, "stop")
            }
        }
        val run = async { runner(model, listOf(write)).run(request, config, inbox = inbox) }
        prepared.await()
        assertTrue(inbox.offer("Do not set that draft"))
        release.complete(Unit)
        val result = run.await()
        assertEquals(RunState.PARTIAL, result.run.state)
        assertEquals(0, writes)
        assertTrue(result.messages.any { it.role == "tool" && it.content.orEmpty().contains("user_update") })
        assertTrue(model.requests.last().any { it.content == "Do not set that draft" })
    }

    @Test fun inputCannotRetractOrReplayAnAlreadyDispatchedAction() = runTest {
        val inbox = AgentInbox()
        val dispatched = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val write = tool("draft", Effect.DRAFT) {
            AgentInvocation.Action(action) {
                writes++
                dispatched.complete(Unit)
                release.await()
                ExecutionResult.Verified(listOf("e1"))
            }
        }
        val model = Model { if (it == 0) calls("draft") else finish("e1") }
        val run = async { runner(model, listOf(write)).run(request, config, inbox = inbox) }
        dispatched.await()
        assertTrue(inbox.offer("Now report the verified status"))
        release.complete(Unit)
        val result = run.await()
        assertEquals(RunState.DONE, result.run.state)
        assertEquals(1, writes)
        val context = model.requests.last()
        assertTrue(context.any { it.role == "tool" && it.content.orEmpty().contains("verified") })
        assertTrue(context.any { it.role == "user" && it.content == "Now report the verified status" })
    }

    @Test fun inputArrivingDuringVerificationPreventsPrematureCompletion() = runTest {
        val inbox = AgentInbox()
        val verifying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = Model {
            when (it) {
                0 -> calls("observe")
                1 -> finish()
                else -> ModelReply(ChatMessage("assistant", "The task changed"), 10, "stop")
            }
        }
        val run = async {
            runner(model, verify = { _, _ ->
                verifying.complete(Unit)
                release.await()
                true
            }).run(request, config, inbox = inbox)
        }
        verifying.await()
        assertTrue(inbox.offer("There is another requirement"))
        release.complete(Unit)
        val result = run.await()
        assertEquals(RunState.PARTIAL, result.run.state)
        assertTrue(model.requests.last().any { it.content == "There is another requirement" })
        assertFalse(inbox.offer("Next run"))
    }

    @Test fun theCoordinatorRejectsDuplicateConversationRunsBeforeTheSecondModelCall() = runTest {
        val coordinator = AgentCoordinator()
        val started = CompletableDeferred<Unit>()
        val firstModel = Model {
            if (it == 0) {
                started.complete(Unit)
                awaitCancellation()
            } else {
                ModelReply(ChatMessage("assistant", "Updated"), 10, "stop")
            }
        }
        val first = async { coordinator.run(runner(firstModel), request, config) }
        started.await()
        val secondModel = Model { ModelReply(ChatMessage("assistant", "Ready"), 10, "stop") }
        val duplicate = runCatching { coordinator.run(runner(secondModel), request.copy(id = "r2"), config) }
        assertTrue(duplicate.exceptionOrNull() is IllegalStateException)
        assertEquals(0, secondModel.calls)
        assertTrue(coordinator.isRunning(request.conversationId))
        assertTrue(coordinator.update(request.conversationId, "Continue with this clarification"))
        assertEquals(RunState.PARTIAL, first.await().run.state)
        assertFalse(coordinator.isRunning(request.conversationId))
        assertFalse(coordinator.update(request.conversationId, "This starts another run"))
        coordinator.run(runner(secondModel), request.copy(id = "r2"), config)
        assertEquals(1, secondModel.calls)
    }

    @Test fun coordinatorStopCancelsItsChildAndReleasesTheConversation() = runTest {
        val coordinator = AgentCoordinator()
        val started = CompletableDeferred<Unit>()
        val saved = mutableListOf<AgentCheckpoint>()
        val model = Model {
            started.complete(Unit)
            awaitCancellation()
        }
        val run = async { coordinator.run(runner(model, saved = saved), request, config) }
        started.await()
        assertTrue(coordinator.stop(request.conversationId))
        assertTrue(runCatching { run.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(RunState.CANCELLED, saved.last().run.state)
        assertFalse(coordinator.isRunning(request.conversationId))
        assertFalse(coordinator.stop(request.conversationId))
    }

    @Test fun updatesBetweenValidationAndDispatchKeepToolResultsBeforeTheNewUserMessage() = runTest {
        val inbox = AgentInbox()
        var prepared = 0
        var offered = false
        val read = tool {
            prepared++
            AgentInvocation.Read { observation }
        }
        val model = Model {
            if (it == 0) calls("observe") else ModelReply(ChatMessage("assistant", "Updated"), 10, "stop")
        }
        val agent = AgentRunner(
            model,
            listOf(read),
            ObservationPrivacyGate(emptySet()),
            { true },
            persist = {
                if (!offered && it.run.summary == "Validated model request") {
                    offered = true
                    assertTrue(inbox.offer("Replace that pending request"))
                }
            },
            retainObservation = {},
            now = { 0 }
        )
        agent.run(request, config, inbox = inbox)
        assertEquals(0, prepared)
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), model.requests.last().map { it.role })
        assertEquals(
            model.requests.last()[2].toolCalls!!.single().id,
            model.requests.last()[3].toolCallId
        )
    }

    @Test fun inboxBoundsPendingInputAndClosesAtomically() {
        val inbox = AgentInbox()
        repeat(8) { assertTrue(inbox.offer("Update $it")) }
        assertFalse(inbox.offer("Beyond the queue limit"))
        assertFalse(inbox.closeIfEmpty())
        assertEquals((0..7).map { "Update $it" }, inbox.drain())
        assertTrue(inbox.closeIfEmpty())
        assertFalse(inbox.offer("Belongs to a later run"))
        assertTrue(inbox.closeAndDrain().isEmpty())
    }

    private suspend fun paused(limits: AgentLimits = AgentLimits()): AgentCheckpoint {
        val ask = tool("ask") { AgentInvocation.Pause(RunState.WAITING_FOR_USER, "Fixture input required") }
        return runner(Model { if (it == 0) calls("observe") else calls("ask") }, listOf(tool(), ask))
            .run(request, config, limits = limits)
    }

    @Test fun resumeRequiresANewObservationBeforeAcceptingOldCompletionEvidence() = runTest {
        val saved = paused()
        var verified = false
        val model = Model { finish() }
        val result = runner(model, verify = { _, _ ->
            verified = true
            true
        }).resume(saved, config, "Continue after checking the app")
        assertEquals(RunState.BLOCKED, result.run.state)
        assertFalse(verified)
        assertEquals(saved.modelRequests + 3, result.modelRequests)
    }

    @Test fun resumePreservesTheGoalAndEvidenceAndCanCompleteAfterReobservation() = runTest {
        val saved = paused()
        val fresh = observation.copy(id = "o2", revision = 2)
        val model = Model { if (it == 0) calls("observe") else finish("o2") }
        val result = runner(model, listOf(tool { AgentInvocation.Read { fresh } }))
            .resume(saved, config, "I have finished the manual step")
        assertEquals(RunState.DONE, result.run.state)
        assertEquals(saved.run.id, result.run.id)
        assertEquals(saved.run.request, result.run.request)
        assertEquals(listOf("o1", "o2"), result.run.evidenceIds)
        assertEquals(saved.modelRequests + 2, result.modelRequests)
        assertTrue(model.requests.first().any { it.content == "I have finished the manual step" })
    }

    @Test fun resumingCannotResetTheModelRequestOrActiveTimeBudget() = runTest {
        val saved = paused(AgentLimits(modelRequests = 3))
        val fresh = tool { AgentInvocation.Read { observation.copy(id = "o2") } }
        val model = Model { calls("observe") }
        val result = runner(model, listOf(fresh)).resume(saved, config, "Continue")
        assertEquals(RunState.PARTIAL, result.run.state)
        assertEquals(3, result.modelRequests)
        assertEquals(1, model.calls)
        val waiting = paused().copy(activeMillis = 9, limits = AgentLimits(activeMillis = 10))
        val slowModel = Model {
            delay(5)
            calls("observe")
        }
        val expired = runner(slowModel, clock = { testScheduler.currentTime }).resume(waiting, config, "Continue")
        assertEquals(RunState.PARTIAL, expired.run.state)
        assertEquals(10L, expired.activeMillis)
        assertEquals(listOf("o1"), expired.run.evidenceIds)
    }

    @Test fun resumeNeverTreatsAnAnswerAsConsentOrReplaysThePendingSend() = runTest {
        var writes = 0
        val send = tool("send", Effect.SEND) {
            AgentInvocation.Action(action.copy(effect = Effect.SEND)) {
                writes++
                ExecutionResult.Verified(listOf("e1"))
            }
        }
        val initial = runner(
            Model { if (it == 0) calls("observe") else calls("send") },
            listOf(tool(), send)
        ).run(request, config)
        assertEquals(RunState.WAITING_FOR_APPROVAL, initial.run.state)
        val fresh = tool { AgentInvocation.Read { observation.copy(id = "o2") } }
        val resumed = runner(Model { if (it == 0) calls("observe") else calls("send") }, listOf(fresh, send))
            .resume(initial, config, "Yes, continue")
        assertEquals(RunState.WAITING_FOR_APPROVAL, resumed.run.state)
        assertEquals(0, writes)
        assertTrue(resumed.pendingAction != null)
    }

    @Test fun interruptedPendingCallsGetUnknownOutcomeResultsInsteadOfReplay() = runTest {
        val saved = paused().let {
            it.copy(run = it.run.copy(state = RunState.INTERRUPTED), messages = it.messages.dropLast(1))
        }
        var reads = 0
        val read = tool {
            reads++
            AgentInvocation.Read { observation }
        }
        val model = Model { ModelReply(ChatMessage("assistant", "Inspect before continuing"), 10, "stop") }
        val result = runner(model, listOf(read)).resume(saved, config, "Inspect the interrupted task")
        assertEquals(RunState.PARTIAL, result.run.state)
        assertEquals(0, reads)
        val context = model.requests.single()
        assertEquals(listOf("tool", "user"), context.takeLast(2).map { it.role })
        assertTrue(context[context.lastIndex - 1].content.orEmpty().contains("interrupted"))
    }

    @Test fun changedProvidersTerminalStatesAndMalformedCheckpointsCannotResume() = runTest {
        val saved = paused()
        val model = Model { calls("observe") }
        val agent = runner(model)
        val invalid = listOf(
            saved.copy(run = saved.run.copy(state = RunState.UNKNOWN)),
            saved.copy(run = saved.run.copy(state = RunState.DONE)),
            saved.copy(modelRequests = -1),
            saved.copy(messages = saved.messages.dropLast(1) + saved.messages.last().copy(toolCallId = "orphan"))
        )
        invalid.forEach {
            assertTrue(
                runCatching {
                    agent.resume(it, config, "Continue")
                }.exceptionOrNull() is IllegalArgumentException
            )
        }
        assertTrue(
            runCatching { agent.resume(saved, config.copy(baseUrl = "https://other.example/v1"), "Continue") }
                .exceptionOrNull() is IllegalArgumentException
        )
        assertEquals(0, model.calls)
    }

    @Test fun startingRunRecordsCannotSupplyInventedCompletionEvidence() = runTest {
        var verified = false
        val result = runner(Model { finish("forged") }, verify = { _, _ ->
            verified = true
            true
        }).run(request.copy(evidenceIds = listOf("forged")), config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertFalse(verified)
        assertTrue(result.run.evidenceIds.isEmpty())
    }

    @Test fun oversizedObservationMetadataIsRejectedBeforeRetentionOrModelUse() = runTest {
        val retained = mutableListOf<Observation>()
        val large = observation.copy(
            nodes = (1..2000).map {
                observation.nodes.single().copy(id = "node$it", label = "")
            }
        )
        val result = runner(
            Model { calls("observe") },
            listOf(tool { AgentInvocation.Read { large } }),
            retained = retained
        ).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertTrue(retained.isEmpty())
        assertTrue(result.run.evidenceIds.isEmpty())
    }

    @Test fun theCompletionVerifierReceivesTheOriginalGoalAndExplicitResumeInstructions() = runTest {
        val saved = paused()
        var verified: AgentCompletion? = null
        val model = Model { if (it == 0) calls("observe") else finish("o2") }
        val agent = AgentRunner(
            model,
            listOf(tool { AgentInvocation.Read { observation.copy(id = "o2") } }),
            ObservationPrivacyGate(emptySet()),
            verifyCompletion = {
                verified = it
                it.goal == request.request && it.updates == listOf("Also verify the manual step")
            },
            persist = {},
            retainObservation = {},
            now = { 0 }
        )
        val result = agent.resume(saved, config, "Also verify the manual step")
        assertEquals(RunState.DONE, result.run.state)
        assertEquals(listOf("o2"), verified?.evidenceIds)
        assertEquals(listOf("Also verify the manual step"), result.userUpdates)
    }

    @Test fun deepArgumentsFromAnAdapterNeverPrepareToolsOrEchoTheirContents() = runTest {
        var prepared = 0
        val read = tool {
            prepared++
            AgentInvocation.Read { observation }
        }
        val arguments = "{\"data\":" + "[".repeat(100) + "0" + "]".repeat(100) + "}"
        val model = Model {
            val reply = calls("observe")
            reply.copy(
                message = reply.message.copy(
                    toolCalls = reply.message.toolCalls!!.map { call ->
                        call.copy(function = call.function.copy(arguments = arguments))
                    }
                )
            )
        }
        val result = runner(model, listOf(read)).run(request, config)
        assertEquals(RunState.BLOCKED, result.run.state)
        assertEquals(0, prepared)
    }

    @Test fun pauseBeforeStartingClosesAdmissionWithoutCallingTheModel() = runTest {
        val inbox = AgentInbox()
        assertTrue(inbox.requestPause())
        assertFalse(inbox.requestPause())
        val model = Model { error("A paused run must not call the provider") }
        val result = runner(model).run(request, config, inbox = inbox)
        assertEquals(RunState.PAUSED, result.run.state)
        assertEquals(0, model.calls)
        assertFalse(inbox.offer("A later update"))
        assertFalse(inbox.requestPause())
    }

    @Test fun coordinatorPauseCancelsAPendingModelRequestAndPreservesTheBudgetForResume() = runTest {
        val coordinator = AgentCoordinator()
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val model = Model {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val task = async { coordinator.run(runner(model), request, config) }
        started.await()
        assertTrue(coordinator.pause(request.conversationId))
        val saved = task.await()
        assertTrue(cancelled)
        assertEquals(RunState.PAUSED, saved.run.state)
        assertEquals(1, saved.modelRequests)
        assertFalse(coordinator.isRunning(request.conversationId))
        assertFalse(coordinator.pause(request.conversationId))
        val fresh = tool { AgentInvocation.Read { observation.copy(id = "new-after-pause") } }
        val resumedModel = Model { if (it == 0) calls("observe") else finish("new-after-pause") }
        val resumed = runner(resumedModel, listOf(fresh)).resume(saved, config, "Continue after the native handover")
        assertEquals(RunState.DONE, resumed.run.state)
        assertEquals(3, resumed.modelRequests)
    }

    @Test fun pauseWaitsForADispatchedEffectAndNeverOverridesAnUncertainOutcome() = runTest {
        for (uncertain in listOf(false, true)) {
            val inbox = AgentInbox()
            val dispatched = CompletableDeferred<Unit>()
            val settle = CompletableDeferred<Unit>()
            val write = tool("write", Effect.DRAFT) {
                AgentInvocation.Action(action) {
                    dispatched.complete(Unit)
                    settle.await()
                    if (uncertain) ExecutionResult.Uncertain else ExecutionResult.Verified(listOf("settled-effect"))
                }
            }
            val model = Model { calls("observe", "write") }
            val task = async { runner(model, listOf(tool(), write)).run(request, config, inbox = inbox) }
            dispatched.await()
            assertTrue(inbox.requestPause())
            assertFalse(task.isCompleted)
            settle.complete(Unit)
            val saved = task.await()
            assertEquals(if (uncertain) RunState.UNKNOWN else RunState.PAUSED, saved.run.state)
            assertEquals(1, model.calls)
            if (!uncertain) assertTrue("settled-effect" in saved.run.evidenceIds)
            assertTrue(saved.messages.filter { it.role == "tool" }.size == 2)
        }
    }

    @Test fun pausingDuringProviderBackoffWakesWithoutAnotherRequest() = runTest {
        val inbox = AgentInbox()
        val waiting = CompletableDeferred<Unit>()
        val model = Model { throw ModelFailure("rate_limit", "Synthetic throttling") }
        val agent = AgentRunner(
            model,
            listOf(tool()),
            ObservationPrivacyGate(emptySet()),
            { true },
            persist = { if (it.run.summary == "Waiting before a bounded model retry") waiting.complete(Unit) },
            retainObservation = {},
            now = { testScheduler.currentTime }
        )
        val task = async { agent.run(request, config, inbox = inbox) }
        waiting.await()
        assertTrue(inbox.requestPause())
        val result = task.await()
        assertEquals(RunState.PAUSED, result.run.state)
        assertEquals(1, model.calls)
        assertEquals(0L, testScheduler.currentTime)
    }
}
