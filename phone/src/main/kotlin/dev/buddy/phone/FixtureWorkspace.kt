package dev.buddy.phone

import android.app.KeyguardManager
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import dev.buddy.core.ActionExecutor
import dev.buddy.core.ActionProposal
import dev.buddy.core.Approval
import dev.buddy.core.DispatchGate
import dev.buddy.core.Effect
import dev.buddy.core.ExecutionResult
import dev.buddy.core.Observation
import dev.buddy.core.UiElement
import dev.buddy.core.sha256
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class FixtureActionKind { DRAFT, SEND, PROTECTED_PAGE, CREDENTIAL_PAGE, SECURE_PAGE }

class PreparedFixtureAction internal constructor(
    internal val owner: FixtureWorkspace,
    internal val kind: FixtureActionKind,
    internal val observation: Observation,
    internal val nodeId: String,
    val proposal: ActionProposal
)

// M0 engineering capability. Its sole app adapter is the controlled, network-free fixture.
// No model tool registry exposes this workspace or its helper handles.
class FixtureWorkspace private constructor(
    private val context: Context,
    private val helper: IBuddyHelper,
    private val executor: ActionExecutor,
    private val lease: String,
    private val token: String,
    private val deadline: Long,
    private val scope: FixtureDisplayScope,
    private val owner: Binder,
    private val recordEvidence: suspend (Observation) -> Unit
) {
    val displayId: Int get() = scope.displayId
    private val closed = AtomicBoolean()
    private val operations = Mutex()
    private val session = UUID.randomUUID().toString()
    private data class ObservationStamp(val fingerprint: String, val revision: Long, val time: Long)
    private val issued = linkedMapOf<String, ObservationStamp>()

    @Volatile private var handedOver = false

    @Volatile private var generation = 0L

    private fun ownedFingerprint(nodes: List<UiElement>): String =
        sha256("$session:${scope.displayId}:$generation:" + fixtureFingerprint(nodes))

    private fun validateDisplay(): Bundle {
        check(!closed.get() && !handedOver && owner.isBinderAlive && SystemClock.elapsedRealtime() < deadline)
        val state = helper.displayState(lease, token)
        check(state.getInt("displayId") == scope.displayId)
        check(state.getInt("width") == scope.width && state.getInt("height") == scope.height)
        return state
    }

    private fun windowPrivacy(state: Bundle) = FixtureWindowPrivacy(
        state.getBoolean("windowMetadataKnown"),
        state.getBoolean("secureWindow"),
        state.getBoolean("mixedWindowOwners"),
        state.getInt("windowMetadataStatus", -1),
        state.getInt("windowMetadataCount"),
        state.getInt("windowMetadataScopedCount"),
        (state.getIntArray("windowMetadataDisplays") ?: intArrayOf()).take(16),
        (state.getIntArray("windowMetadataForeign") ?: intArrayOf()).take(64),
        (state.getIntArray("windowMetadataForeignGeometry") ?: intArrayOf()).take(48),
        state.getString("windowMetadataMonitorProbe", "unavailable").take(256)
    )

    private fun snapshot(): FixtureSnapshot {
        val startedGeneration = generation
        val first = FixtureScreenService.capture(scope, windowPrivacy(validateDisplay()))
        val after = windowPrivacy(validateDisplay())
        val captured = if (!after.known || after.secure || after.mixed) {
            FixtureScreenService.capture(scope, after)
        } else {
            first
        }
        check(startedGeneration == generation && !handedOver && !closed.get())
        val bound = captured.copy(
            observation = captured.observation.copy(
                fingerprint = ownedFingerprint(captured.observation.nodes)
            )
        )
        return bound.also { snapshot ->
            val observation = snapshot.observation
            synchronized(issued) {
                issued[observation.id] = ObservationStamp(
                    observation.fingerprint,
                    observation.revision,
                    observation.capturedAtElapsedMs
                )
                while (issued.size > 32) issued.remove(issued.keys.first())
            }
        }
    }

    suspend fun observe(): Observation = withContext(Dispatchers.IO) { snapshot().observation }

    private fun prepare(
        kind: FixtureActionKind,
        observation: Observation,
        node: UiElement,
        payload: String,
        effect: Effect,
        target: String,
        destination: String
    ): PreparedFixtureAction {
        check(observation.runId == scope.runId && observation.app == FIXTURE_PACKAGE)
        check(observation.width == scope.width && observation.height == scope.height)
        check(
            synchronized(issued) {
                issued[observation.id] == ObservationStamp(
                    observation.fingerprint,
                    observation.revision,
                    observation.capturedAtElapsedMs
                )
            }
        ) { "Observe this workspace before preparing an action" }
        check(!closed.get() && !handedOver)
        check(observation.fingerprint == ownedFingerprint(observation.nodes))
        check(!observation.credentialSurface && !observation.protected && !observation.excludedOverlay)
        check(observation.nodes.all { it.packageName == FIXTURE_PACKAGE && !it.password })
        check(SystemClock.elapsedRealtime() - observation.capturedAtElapsedMs in 0..5_000)
        require(node in observation.nodes)
        return PreparedFixtureAction(
            this,
            kind,
            observation,
            node.id,
            ActionProposal(
                UUID.randomUUID().toString(), scope.runId, observation.id, observation.revision,
                FIXTURE_PACKAGE, effect, target, payload, destination, observation.fingerprint
            )
        )
    }

    fun prepareDraft(observation: Observation, text: String): PreparedFixtureAction {
        require(text.length <= 1024)
        val editor = observation.nodes.single { it.editable }
        return prepare(
            FixtureActionKind.DRAFT,
            observation,
            editor,
            text,
            Effect.DRAFT,
            editor.id,
            "fixture draft"
        )
    }

    fun prepareSend(observation: Observation): PreparedFixtureAction {
        val editor = observation.nodes.single { it.editable }
        val send = observation.nodes.single { it.label.equals("Send to fixture inbox", ignoreCase = true) }
        require(editor.label.isNotBlank())
        return prepare(
            FixtureActionKind.SEND,
            observation,
            send,
            editor.label,
            Effect.SEND,
            "Alice",
            "fixture inbox"
        )
    }

    fun prepareProtectedPage(observation: Observation): PreparedFixtureAction =
        preparePage(observation, "Show protected page", FixtureActionKind.PROTECTED_PAGE)

    fun prepareCredentialPage(observation: Observation): PreparedFixtureAction =
        preparePage(observation, "Show credential page", FixtureActionKind.CREDENTIAL_PAGE)

    fun prepareSecurePage(observation: Observation): PreparedFixtureAction =
        preparePage(observation, "Show secure page", FixtureActionKind.SECURE_PAGE)

    private fun preparePage(observation: Observation, label: String, kind: FixtureActionKind): PreparedFixtureAction {
        val button = observation.nodes.single { it.label.equals(label, ignoreCase = true) }
        return prepare(kind, observation, button, "", Effect.NAVIGATE, button.id, "fixture private page")
    }

    suspend fun execute(
        prepared: PreparedFixtureAction,
        approval: Approval? = null,
        gate: suspend () -> DispatchGate
    ): ExecutionResult = withContext(Dispatchers.IO) {
        operations.withLock {
            require(prepared.owner === this@FixtureWorkspace)
            if (prepared.observation.fingerprint != ownedFingerprint(prepared.observation.nodes)) {
                return@withLock ExecutionResult.Blocked("Observe the workspace again after takeover")
            }
            var resolved: FixtureSnapshot? = null
            executor.execute(
                prepared.proposal,
                prepared.observation,
                approval,
                gate = {
                    val requested = gate()
                    val keyguard = context.getSystemService(KeyguardManager::class.java)
                    val owns = runCatching {
                        val state = validateDisplay()
                        state.getBoolean("windowMetadataKnown") && !state.getBoolean("secureWindow") &&
                            !state.getBoolean("mixedWindowOwners")
                    }.getOrDefault(false)
                    requested.copy(
                        ownsApp = requested.ownsApp && owns,
                        locked =
                            requested.locked || keyguard.isDeviceLocked || keyguard.isKeyguardLocked ||
                                !context.getSystemService(PowerManager::class.java).isInteractive,
                        takeover = requested.takeover || handedOver,
                        leaseDeadline = minOf(requested.leaseDeadline, deadline),
                        authorizationDeadline = minOf(requested.authorizationDeadline, deadline)
                    )
                },
                prepare = {
                    val fresh = snapshot()
                    check(fresh.observation.fingerprint == prepared.observation.fingerprint)
                    check(
                        !fresh.observation.protected && !fresh.observation.credentialSurface &&
                            !fresh.observation.excludedOverlay
                    )
                    val node = requireNotNull(fresh.nodes[prepared.nodeId])
                    check(node.isVisibleToUser)
                    resolved = fresh
                },
                dispatch = {
                    val fresh = requireNotNull(resolved)
                    val node = requireNotNull(fresh.nodes[prepared.nodeId])
                    currentCoroutineContext().ensureActive()
                    when (prepared.kind) {
                        FixtureActionKind.DRAFT -> FixtureScreenService.edit(
                            fresh,
                            prepared.nodeId,
                            prepared.proposal.payload,
                            scope.displayId
                        )

                        FixtureActionKind.SEND, FixtureActionKind.PROTECTED_PAGE,
                        FixtureActionKind.CREDENTIAL_PAGE, FixtureActionKind.SECURE_PAGE -> {
                            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                            helper.tap(
                                lease,
                                token,
                                prepared.proposal.id,
                                bounds.centerX(),
                                bounds.centerY()
                            )
                        }
                    }
                },
                verify = {
                    val until = SystemClock.elapsedRealtime() + 2_000
                    var evidence = emptyList<String>()
                    while (SystemClock.elapsedRealtime() < until) {
                        val after = try {
                            observe()
                        } catch (_: FixtureScreenChanged) {
                            delay(50)
                            continue
                        }
                        val verified = when (prepared.kind) {
                            FixtureActionKind.DRAFT -> after.nodes.any {
                                it.editable &&
                                    it.label == prepared.proposal.payload
                            }

                            FixtureActionKind.SEND -> after.nodes.any {
                                it.label ==
                                    "Sent to Alice: ${prepared.proposal.payload}"
                            }

                            FixtureActionKind.PROTECTED_PAGE ->
                                (after.protected || after.credentialSurface) && after.nodes.isEmpty()

                            FixtureActionKind.CREDENTIAL_PAGE -> after.credentialSurface && after.nodes.isEmpty()

                            FixtureActionKind.SECURE_PAGE -> after.protected && after.nodes.isEmpty()
                        }
                        if (verified) {
                            // Persist the filtered observation before the journal can mark this action verified.
                            recordEvidence(after)
                            evidence = listOf(after.id)
                            break
                        }
                        if (after.protected || after.credentialSurface || after.excludedOverlay) break
                        delay(50)
                    }
                    evidence
                }
            )
        }
    }

    suspend fun takeOver() = withContext(Dispatchers.IO) {
        operations.withLock {
            validateDisplay()
            handedOver = true
            generation++
            synchronized(issued) { issued.clear() }
            helper.takeOverFixture(lease, token, UUID.randomUUID().toString())
        }
    }

    suspend fun returnToWorkspace() = withContext(Dispatchers.IO) {
        operations.withLock {
            check(!closed.get() && handedOver)
            helper.returnFixture(lease, token, UUID.randomUUID().toString())
            generation++
            synchronized(issued) { issued.clear() }
            handedOver = false
            validateDisplay()
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        if (closed.compareAndSet(false, true)) runCatching { helper.releaseLease(lease) }
        Unit
    }

    companion object {
        suspend fun open(
            context: Context,
            helper: IBuddyHelper,
            executor: ActionExecutor,
            surface: Surface,
            runId: String,
            recordEvidence: suspend (Observation) -> Unit,
            width: Int = 720,
            height: Int = 1280,
            dpi: Int = 240
        ): FixtureWorkspace {
            var created: FixtureWorkspace? = null
            try {
                return withContext(Dispatchers.IO) {
                    require(runId.length in 1..128)
                    val caps = helper.capabilities()
                    check(
                        caps.getInt("protocol") == ShellProtocol.VERSION &&
                            caps.getInt("helperUid") == ShellProtocol.SHELL_UID
                    )
                    val owner = Binder()
                    val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
                    val acquired = try {
                        helper.acquireLease(owner, challenge)
                    } finally {
                        challenge.fill(0)
                    }
                    val lease = requireNotNull(acquired.getString("lease"))
                    try {
                        val deadline = acquired.getLong("deadline")
                        check(deadline - SystemClock.elapsedRealtime() in 1..ShellProtocol.LEASE_MS)
                        val token = helper.createDisplay(lease, width, height, dpi, surface)
                        val display = helper.launchFixture(lease, token, UUID.randomUUID().toString())
                        FixtureWorkspace(
                            context.applicationContext,
                            helper,
                            executor,
                            lease,
                            token,
                            deadline,
                            FixtureDisplayScope(display, width, height, runId),
                            owner,
                            recordEvidence
                        ).also { created = it }
                    } catch (failure: Exception) {
                        runCatching { helper.releaseLease(lease) }
                        throw failure
                    }
                }
            } catch (failure: Exception) {
                // withContext can cancel while returning a newly created resource to its caller.
                created?.close()
                throw failure
            }
        }
    }
}
