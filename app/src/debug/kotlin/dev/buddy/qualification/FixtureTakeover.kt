package dev.buddy.qualification

import android.content.Context
import android.os.SystemClock
import dev.buddy.core.ActionExecutor
import dev.buddy.core.DispatchGate
import dev.buddy.core.ExecutionResult
import dev.buddy.phone.FixtureWorkspace
import dev.buddy.shell.IBuddyHelper
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

internal enum class FixtureTakeoverPhase { PREPARING, READY, MOVING, HUMAN, RETURNING, CLOSED }

internal class FixtureTakeoverControl {
    private val lock = Any()
    private val take = CompletableDeferred<Unit>()
    private val back = CompletableDeferred<Unit>()
    private var phase = FixtureTakeoverPhase.PREPARING
    fun request(returning: Boolean): Boolean = synchronized(lock) {
        val expected = if (returning) FixtureTakeoverPhase.HUMAN else FixtureTakeoverPhase.READY
        if (phase != expected) return@synchronized false
        phase = if (returning) FixtureTakeoverPhase.RETURNING else FixtureTakeoverPhase.MOVING
        if (returning) back.complete(Unit) else take.complete(Unit)
        true
    }
    suspend fun awaitTakeover(ready: suspend (FixtureTakeoverPhase) -> Unit) {
        synchronized(lock) {
            check(phase == FixtureTakeoverPhase.PREPARING)
            phase = FixtureTakeoverPhase.READY
        }
        ready(FixtureTakeoverPhase.READY)
        take.await()
    }
    suspend fun awaitReturn(ready: suspend (FixtureTakeoverPhase) -> Unit) {
        synchronized(lock) {
            check(phase == FixtureTakeoverPhase.MOVING)
            phase = FixtureTakeoverPhase.HUMAN
        }
        ready(FixtureTakeoverPhase.HUMAN)
        back.await()
    }
    fun close() = synchronized(lock) {
        phase = FixtureTakeoverPhase.CLOSED
        take.cancel()
        back.cancel()
    }
}

internal object FixtureTakeoverCheck {
    suspend fun run(
        context: Context,
        helper: IBuddyHelper,
        control: FixtureTakeoverControl,
        admitted: () -> Boolean,
        refreshAuthorization: suspend () -> Long,
        stopTransport: () -> Unit,
        phase: suspend (FixtureTakeoverPhase) -> Unit,
        preview: suspend (FixturePreview?) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        coroutineScope {
            val run = UUID.randomUUID().toString()
            val journal = ForegroundFixtureCheck.Journal()
            ForegroundFixtureCheck.Drain().use { frames ->
                var workspace: FixtureWorkspace? = null
                var maintenance: kotlinx.coroutines.Job? = null
                try {
                    check(admitted())
                    val opened = FixtureWorkspace.open(
                        context,
                        helper,
                        ActionExecutor(journal, SystemClock::elapsedRealtime),
                        frames.surface,
                        run,
                        recordEvidence = { preview(FixturePreview.from(it)) }
                    )
                    workspace = opened
                    maintenance = launch { opened.maintainAuthorization(refreshAuthorization) }
                    val active = currentCoroutineContext()
                    suspend fun gate() = DispatchGate(
                        cancelled = !active.isActive,
                        paused = !admitted(),
                        locked = false,
                        supervised = admitted(),
                        ownsApp = true,
                        leaseDeadline = Long.MAX_VALUE,
                        authorizationDeadline = Long.MAX_VALUE,
                        takeover = false
                    )
                    suspend fun fresh() = withTimeout(5_000) {
                        while (true) {
                            check(admitted())
                            try {
                                return@withTimeout opened.observe()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: IllegalStateException) {
                                delay(50)
                            }
                        }
                        error("Fresh fixture observation unavailable")
                    }
                    val initial = withTimeout(5_000) {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(admitted())
                            val observation = fresh()
                            if (!observation.protected && !observation.credentialSurface &&
                                !observation.excludedOverlay &&
                                observation.nodes.count { it.editable && !it.password } == 1
                            ) {
                                return@withTimeout observation
                            }
                            delay(50)
                        }
                        error("Fixture editor unavailable")
                    }
                    val draft = opened.prepareDraft(initial, "Private takeover check ${run.take(8)}")
                    check(opened.execute(draft, gate = ::gate) is ExecutionResult.Verified)
                    control.awaitTakeover(phase)
                    check(admitted())
                    preview(null)
                    opened.takeOver()
                    control.awaitReturn(phase)
                    check(admitted())
                    opened.returnToWorkspace()
                    // Read the moved task anew; no prepared action survives the handover.
                    withTimeout(5_000) {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            check(admitted())
                            val observation = fresh()
                            if (observation.protected || observation.credentialSurface) {
                                preview(FixturePreview.from(observation))
                                return@withTimeout false
                            }
                            if (observation.excludedOverlay) {
                                preview(FixturePreview.from(observation))
                                delay(50)
                                continue
                            }
                            if (observation.nodes.any { it.editable && it.label == "human" }) {
                                preview(FixturePreview.from(observation))
                                return@withTimeout true
                            }
                            delay(50)
                        }
                        error("Returned fixture unavailable")
                    }
                } finally {
                    control.close()
                    stopTransport()
                    withContext(NonCancellable) {
                        maintenance?.cancelAndJoin()
                        workspace?.close()
                        preview(null)
                        phase(FixtureTakeoverPhase.CLOSED)
                    }
                    journal.clear()
                }
            }
        }
    }
}
