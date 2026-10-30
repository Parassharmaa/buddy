package dev.buddy.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ActionJournal {
    suspend fun unresolved(operationKey: String): Boolean

    // Implementations atomically save Dispatching, reserve the operation and consume approval.
    suspend fun begin(record: ActionRecord, approval: Approval?)
    suspend fun update(record: ActionRecord)
    suspend fun markUncertain(record: ActionRecord)
}

data class DispatchGate(
    val cancelled: Boolean,
    val paused: Boolean,
    val locked: Boolean,
    val supervised: Boolean,
    val ownsApp: Boolean,
    val leaseDeadline: Long,
    val authorizationDeadline: Long,
    val takeover: Boolean
)

sealed interface ExecutionResult {
    data class Verified(val evidenceIds: List<String>) : ExecutionResult
    data class Blocked(val reason: String) : ExecutionResult
    data object ApprovalRequired : ExecutionResult
    data object TakeoverRequired : ExecutionResult
    data object Uncertain : ExecutionResult
}

class ActionExecutor(private val journal: ActionJournal, private val now: () -> Long) {
    private val mutex = Mutex()

    suspend fun execute(
        action: ActionProposal,
        observation: Observation,
        approval: Approval?,
        gate: suspend () -> DispatchGate,
        dispatch: suspend () -> Unit,
        verify: suspend () -> List<String>
    ): ExecutionResult = mutex.withLock {
        if (journal.unresolved(
                action.operationKey
            )
        ) {
            return@withLock ExecutionResult.Blocked(
                "Inspect the previous uncertain action before retrying"
            )
        }
        if (observation.protected || observation.credentialSurface ||
            observation.excludedOverlay
        ) {
            return@withLock ExecutionResult.TakeoverRequired
        }
        if (action.runId != observation.runId || action.app != observation.app ||
            action.observationId != observation.id || action.revision != observation.revision ||
            action.fingerprint != observation.fingerprint ||
            now() - observation.capturedAtElapsedMs !in 0..5_000
        ) {
            return@withLock ExecutionResult.Blocked("Observe the current screen again")
        }
        if (action.effect in
            setOf(Effect.PAYMENT, Effect.CREDENTIAL, Effect.SECURITY, Effect.UNKNOWN)
        ) {
            return@withLock ExecutionResult.TakeoverRequired
        }
        val needsApproval = action.effect in setOf(Effect.SEND, Effect.DELETE, Effect.SUBMIT)
        if (needsApproval &&
            (action.target.isBlank() || action.destination.isBlank() || action.payload.isBlank())
        ) {
            return@withLock ExecutionResult.TakeoverRequired
        }
        if (needsApproval && (
                approval == null || !approval.accepted || approval.consumed ||
                    approval.runId != action.runId || approval.operationKey != action.operationKey ||
                    approval.fingerprint != action.fingerprint ||
                    approval.expiresAtElapsedMs <= now()
                )
        ) {
            return@withLock ExecutionResult.ApprovalRequired
        }
        val preflight = gate()
        if (preflight.cancelled || preflight.paused || preflight.locked || !preflight.supervised ||
            !preflight.ownsApp || preflight.takeover ||
            now() >= minOf(preflight.leaseDeadline, preflight.authorizationDeadline)
        ) {
            return@withLock ExecutionResult.Blocked("Phone control is paused or unavailable")
        }
        val record = ActionRecord(action, DispatchState.DISPATCHING)
        journal.begin(record, if (needsApproval) approval else null)
        // Recheck cancellation/lock after durable intent commit and immediately before IPC.
        val finalGate = gate()
        if (finalGate != preflight ||
            now() >= minOf(finalGate.leaseDeadline, finalGate.authorizationDeadline)
        ) {
            journal.markUncertain(record)
            return@withLock ExecutionResult.Uncertain
        }
        try {
            dispatch()
            journal.update(record.copy(state = DispatchState.DISPATCHED))
            val evidence = verify()
            if (evidence.isEmpty()) {
                journal.markUncertain(record)
                ExecutionResult.Uncertain
            } else {
                journal.update(record.copy(state = DispatchState.VERIFIED, evidenceIds = evidence))
                ExecutionResult.Verified(evidence)
            }
        } catch (cancelled: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                journal.markUncertain(record)
            }
            throw cancelled
        } catch (_: Exception) {
            journal.markUncertain(record)
            ExecutionResult.Uncertain
        }
    }
}
