package dev.buddy.qualification

import android.content.Context
import android.os.SystemClock
import dev.buddy.core.ActionExecutor
import dev.buddy.core.DispatchGate
import dev.buddy.core.Effect
import dev.buddy.core.ExecutionResult
import dev.buddy.core.ModelConfig
import dev.buddy.core.Observation
import dev.buddy.core.ObservationPrivacyGate
import dev.buddy.core.RunRecord
import dev.buddy.core.RunState
import dev.buddy.core.agent.AgentInbox
import dev.buddy.core.agent.AgentInvocation
import dev.buddy.core.agent.AgentLimits
import dev.buddy.core.agent.AgentRunner
import dev.buddy.core.agent.AgentTool
import dev.buddy.core.model.ChatModel
import dev.buddy.phone.FixtureWorkspace
import dev.buddy.shell.IBuddyHelper
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal object ModelFixtureCheck {
    suspend fun run(
        context: Context,
        helper: IBuddyHelper,
        config: ModelConfig,
        model: ChatModel,
        visible: () -> Boolean,
        refreshAuthorization: suspend () -> Long,
        stopTransport: () -> Unit,
        stage: suspend (String) -> Unit,
        preview: suspend (FixturePreview?) -> Unit = {},
        control: FixtureTakeoverControl? = null,
        phase: suspend (FixtureTakeoverPhase) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        coroutineScope {
            check(visible())
            val run = UUID.randomUUID().toString()
            val baseline = "Buddy fixture baseline ${run.take(8)}"
            val goalText = "Buddy model fixture ${run.take(8)} · नमस्ते · こんにちは · café · 😀"
            val retained = linkedMapOf<String, Observation>()
            var native: Observation? = null
            val projection = FixtureModelProjection(baseline, goalText)
            fun project(observation: Observation) = projection.project(observation)
            suspend fun retain(observation: Observation) {
                check(observation.id in retained || retained.size < 32)
                val projected = project(observation)
                retained[observation.id] = projected
                preview(FixturePreview.from(projected))
            }
            val journal = ForegroundFixtureCheck.Journal()
            ForegroundFixtureCheck.Drain().use { frames ->
                var workspace: FixtureWorkspace? = null
                var maintenance: kotlinx.coroutines.Job? = null
                var controlJob: kotlinx.coroutines.Job? = null
                try {
                    stage("Opening the controlled model fixture")
                    val opened = FixtureWorkspace.open(
                        context,
                        helper,
                        ActionExecutor(journal, SystemClock::elapsedRealtime),
                        frames.surface,
                        run,
                        recordEvidence = ::retain
                    )
                    workspace = opened
                    maintenance = launch { opened.maintainAuthorization(refreshAuthorization) }
                    val active = currentCoroutineContext()
                    fun gate(current: Boolean = true) = DispatchGate(
                        cancelled = !active.isActive || !current,
                        paused = !visible(),
                        locked = false,
                        supervised = visible(),
                        ownsApp = true,
                        leaseDeadline = Long.MAX_VALUE,
                        authorizationDeadline = Long.MAX_VALUE,
                        takeover = false
                    )
                    val initial = withTimeout(5_000) {
                        var observed: Observation
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(visible())
                            observed = opened.observe()
                            if (!observed.protected && !observed.credentialSurface && !observed.excludedOverlay &&
                                observed.nodes.count { it.editable && !it.password } == 1
                            ) {
                                break
                            }
                            delay(50)
                        }
                        observed
                    }
                    check(
                        opened.execute(opened.prepareDraft(initial, baseline), gate = { gate() }) is
                            ExecutionResult.Verified
                    )
                    val schema = Json.parseToJsonElement(
                        """{"type":"object","additionalProperties":false,"properties":{}}"""
                    ).jsonObject
                    fun tool(name: String, effect: Effect, prepare: suspend () -> AgentInvocation) =
                        object : AgentTool {
                            override val name = name
                            override val description = "Controlled offline fixture qualification with fixed test text"
                            override val effect = effect
                            override val parameters = schema
                            override fun validate(arguments: JsonObject) = require(arguments.isEmpty())
                            override suspend fun prepare(arguments: JsonObject) = prepare()
                        }
                    val tools = listOf(
                        tool("fixture_observe", Effect.READ) {
                            AgentInvocation.Read {
                                check(visible())
                                opened.observe().also { native = it }.let(::project)
                            }
                        },
                        tool("fixture_set_test_draft", Effect.DRAFT) {
                            val action = opened.prepareDraft(requireNotNull(native), goalText)
                            AgentInvocation.Action(action.proposal) { dispatch ->
                                opened.execute(action, dispatch.approval, gate = { gate(dispatch.isCurrent()) })
                            }
                        }
                    )
                    val goal = "Set the controlled fixture draft to exactly: $goalText"
                    val runner = AgentRunner(
                        model,
                        tools,
                        ObservationPrivacyGate(emptySet()),
                        verifyCompletion = { completion ->
                            check(visible())
                            completion.goal == goal && completion.evidenceIds.all { it in retained } &&
                                opened.observe().nodes.any { it.editable && it.label == goalText }
                        },
                        persist = { stage("Model fixture: ${it.modelRequests} requests · ${it.run.state}") },
                        retainObservation = ::retain,
                        now = SystemClock::elapsedRealtime
                    )
                    var inbox = AgentInbox()
                    controlJob = control?.let { nativeControl ->
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            nativeControl.awaitTakeover(phase)
                            inbox.requestPause()
                        }
                    }
                    val result = withTimeout(if (control == null) 65_000 else 95_000) {
                        var checkpoint = runner.run(
                            RunRecord(run, "engineering-fixture", goal, createdAt = System.currentTimeMillis()),
                            config,
                            limits = AgentLimits(modelRequests = 6, activeMillis = 60_000, contextBytes = 16_384),
                            inbox = inbox
                        )
                        if (control != null &&
                            checkpoint.run.state in setOf(RunState.PAUSED, RunState.WAITING_FOR_USER)
                        ) {
                            stage("Waiting for native takeover")
                            requireNotNull(controlJob).join()
                            check(visible())
                            preview(null)
                            native = null
                            opened.takeOver()
                            control.awaitReturn(phase)
                            check(visible())
                            opened.returnToWorkspace()
                            val returned = withTimeout(5_000) {
                                while (true) {
                                    check(visible())
                                    try {
                                        val observation = opened.observe()
                                        check(!observation.protected && !observation.credentialSurface)
                                        if (!observation.excludedOverlay) return@withTimeout observation
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: IllegalStateException) {
                                        // A structural change can invalidate a read; no action is retried.
                                    }
                                    delay(50)
                                }
                                error("Returned fixture unavailable")
                            }
                            retain(returned)
                            native = returned
                            inbox = AgentInbox()
                            checkpoint = runner.resume(
                                checkpoint,
                                config,
                                "Native user returned the fixture. Reobserve before continuing the fixed test task.",
                                inbox
                            )
                        }
                        checkpoint
                    }
                    check(result.run.state == RunState.DONE)
                    check(visible())
                } finally {
                    control?.close()
                    stopTransport()
                    withContext(NonCancellable) {
                        controlJob?.cancelAndJoin()
                        maintenance?.cancelAndJoin()
                        workspace?.close()
                    }
                    retained.clear()
                    native = null
                    journal.clear()
                    withContext(NonCancellable) {
                        preview(null)
                        if (control != null) phase(FixtureTakeoverPhase.CLOSED)
                    }
                }
            }
        }
    }
}
