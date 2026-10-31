package dev.buddy.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExecutorTest {
    private val observation =
        Observation("o1", "r1", 1, 1_000, "fixture", 100, 200, emptyList(), "fingerprint")
    private val action =
        ActionProposal("a1", "r1", "o1", 1, "fixture", Effect.SEND, "Alice", "Hello", "fixture inbox", "fingerprint")
    private val approval =
        Approval("p1", "r1", action.operationKey, "fingerprint", 10_000, accepted = true)
    private val gate = DispatchGate(false, false, false, true, true, 20_000, 20_000, false)
    private class Journal(private val afterBegin: () -> Unit = {}) : ActionJournal {
        val barriers = mutableSetOf<String>()
        val consumed = mutableSetOf<String>()
        val records = mutableListOf<ActionRecord>()
        override suspend fun unresolved(operationKey: String) = operationKey in barriers
        override suspend fun begin(record: ActionRecord, approval: Approval?) {
            check(approval == null || consumed.add(approval.id))
            records += record
            barriers += record.proposal.operationKey
            afterBegin()
        }
        override suspend fun update(record: ActionRecord) {
            records += record
            if (record.state in setOf(DispatchState.VERIFIED, DispatchState.NOT_DISPATCHED)) {
                barriers.remove(record.proposal.operationKey)
            }
        }
        override suspend fun markUncertain(record: ActionRecord) {
            records += record.copy(state = DispatchState.UNCERTAIN)
            barriers +=
                record.proposal.operationKey
        }
    }

    @Test fun approvalAndFreshObservationAreRequired() = runTest {
        val journal = Journal()
        val executor = ActionExecutor(journal) { 2_000 }
        var writes = 0
        suspend fun execute(a: ActionProposal, p: Approval?) =
            executor.execute(a, observation, p, { gate }, { writes++ }, { listOf("e1") })
        assertEquals(ExecutionResult.ApprovalRequired, execute(action, null))
        assertEquals(
            ExecutionResult.ApprovalRequired,
            execute(action.copy(payload = "Changed"), approval)
        )
        assertTrue(execute(action.copy(revision = 0), approval) is ExecutionResult.Blocked)
        assertTrue(execute(action, approval) is ExecutionResult.Verified)
        assertEquals(1, writes)
        assertEquals(setOf("p1"), journal.consumed)
    }

    @Test fun unknownOutcomeBlocksSameOperationUnderNewIdAndRun() = runTest {
        val journal = Journal()
        val executor = ActionExecutor(journal) { 2_000 }
        var writes = 0
        assertEquals(
            ExecutionResult.Uncertain,
            executor.execute(action, observation, approval, {
                gate
            }, { writes++ }, { emptyList() })
        )
        assertEquals(ActionFailure.NO_VERIFICATION_EVIDENCE, journal.records.last().failure)
        assertTrue(
            executor.execute(action.copy(id = "new", runId = "new-run"), observation, approval, {
                gate
            }, { writes++ }, { listOf("fake") }) is ExecutionResult.Blocked
        )
        assertEquals(1, writes)
    }

    @Test fun lockCancellationAndExpiredAuthorizationPreventDispatch() = runTest {
        for (blocked in listOf(
            gate.copy(locked = true),
            gate.copy(cancelled = true),
            gate.copy(supervised = false),
            gate.copy(authorizationDeadline = 1_999),
            gate.copy(takeover = true)
        )) {
            var writes = 0
            val result = ActionExecutor(Journal()) {
                2_000
            }.execute(action, observation, approval, { blocked }, { writes++ }, { listOf("e1") })
            assertTrue(result is ExecutionResult.Blocked)
            assertEquals(0, writes)
        }
    }

    @Test fun fingerprintChangeInvalidatesConsentButClockRevisionDoesNot() = runTest {
        val executor = ActionExecutor(Journal()) { 2_000 }
        val fresh = observation.copy(id = "o2", revision = 2, capturedAtElapsedMs = 2_000)
        val refreshed = action.copy(observationId = "o2", revision = 2)
        assertTrue(
            executor.execute(refreshed, fresh, approval, {
                gate
            }, {}, { listOf("e2") }) is ExecutionResult.Verified
        )
        assertEquals(
            ExecutionResult.ApprovalRequired,
            executor.execute(
                refreshed.copy(
                    fingerprint = "changed"
                ),
                fresh.copy(fingerprint = "changed"),
                approval,
                {
                    gate
                },
                {},
                { listOf("e2") }
            )
        )
    }

    @Test fun paymentAndUnknownEffectsAlwaysRequireHuman() = runTest {
        for (effect in listOf(Effect.PAYMENT, Effect.CREDENTIAL, Effect.UNKNOWN, Effect.SECURITY)) {
            val result = ActionExecutor(Journal()) {
                2_000
            }.execute(action.copy(effect = effect), observation, approval, {
                gate
            }, { fail("Must not dispatch") }, { listOf("e") })
            assertEquals(ExecutionResult.TakeoverRequired, result)
        }
    }

    @Test fun privacyGateSuppressesMixedExcludedAndPasswordWindows() {
        val filter = ObservationPrivacyGate(setOf("bank"))
        assertTrue(filter.filter(observation) is FilteredObservation.Allowed)
        assertTrue(
            filter.filter(
                observation.copy(credentialSurface = true)
            ) is FilteredObservation.Suppressed
        )
        val node = UiElement("n", "secret", "fixture", Bounds(0, 0, 10, 10), password = true)
        assertTrue(
            filter.filter(observation.copy(nodes = listOf(node))) is FilteredObservation.Suppressed
        )
        assertTrue(
            filter.filter(
                observation.copy(nodes = listOf(node.copy(password = false, packageName = "bank")))
            ) is FilteredObservation.Suppressed
        )
    }

    @Test fun operationHashCannotBeConfusedByNulInUserText() {
        val first = action.copy(target = "Alice\u0000Hello", payload = "World")
        val second = action.copy(target = "Alice", payload = "Hello\u0000World")
        assertNotEquals(first.operationKey, second.operationKey)
    }

    @Test fun observationCannotExpireDuringDurableCommit() = runTest {
        var clock = 2_000L
        val journal = Journal { clock = 7_001L }
        var dispatched = false
        val result = ActionExecutor(journal) { clock }.execute(
            action,
            observation,
            approval,
            { gate },
            { dispatched = true },
            { listOf("e1") }
        )
        assertTrue(result is ExecutionResult.Blocked)
        assertTrue(!dispatched)
        assertEquals(DispatchState.NOT_DISPATCHED, journal.records.last().state)
        assertEquals(ActionFailure.FINAL_GATE_CHANGED, journal.records.last().failure)
        assertTrue(!journal.unresolved(action.operationKey))
    }

    @Test fun approvalCannotExpireDuringDurableCommit() = runTest {
        var clock = 2_000L
        val journal = Journal { clock = 3_000L }
        var writes = 0
        val result = ActionExecutor(journal) { clock }.execute(
            action,
            observation,
            approval.copy(expiresAtElapsedMs = 2_500),
            { gate },
            { writes++ },
            { listOf("e1") }
        )
        assertEquals(ExecutionResult.ApprovalRequired, result)
        assertEquals(0, writes)
        assertEquals(DispatchState.NOT_DISPATCHED, journal.records.last().state)
        assertTrue(!journal.unresolved(action.operationKey))
        assertEquals(setOf(approval.id), journal.consumed)
    }

    @Test fun approvalExpiringDuringTargetResolutionCannotReachDispatch() = runTest {
        var clock = 2_000L
        val journal = Journal()
        var writes = 0
        val result = ActionExecutor(journal) { clock }.execute(
            action,
            observation,
            approval.copy(expiresAtElapsedMs = 2_500),
            { gate },
            { writes++ },
            { listOf("e1") },
            prepare = { clock = 3_000L }
        )
        assertEquals(ExecutionResult.ApprovalRequired, result)
        assertEquals(0, writes)
        assertEquals(DispatchState.NOT_DISPATCHED, journal.records.last().state)
        assertTrue(!journal.unresolved(action.operationKey))
    }

    @Test fun cancellationDuringDurableCommitCannotReachANonSuspendingDispatch() = runTest {
        var writes = 0
        lateinit var journal: Journal
        val running = launch {
            val context = currentCoroutineContext()
            journal = Journal { context.cancel() }
            ActionExecutor(journal) { 2_000 }.execute(
                action,
                observation,
                approval,
                { gate },
                { writes++ },
                { listOf("e1") }
            )
        }
        running.join()
        assertTrue(running.isCancelled)
        assertEquals(0, writes)
        assertEquals(DispatchState.NOT_DISPATCHED, journal.records.last().state)
        assertTrue(!journal.unresolved(action.operationKey))
        assertEquals(setOf(approval.id), journal.consumed)
    }

    @Test fun cancellationDuringTheFinalGateClearsTheUndispatchedIntent() = runTest {
        val checking = CompletableDeferred<Unit>()
        val journal = Journal()
        var gates = 0
        var writes = 0
        val running = launch {
            ActionExecutor(journal) { 2_000 }.execute(action, observation, approval, {
                if (++gates == 2) {
                    checking.complete(Unit)
                    awaitCancellation()
                }
                gate
            }, { writes++ }, { listOf("e1") })
        }
        checking.await()
        running.cancelAndJoin()
        assertEquals(0, writes)
        assertEquals(DispatchState.NOT_DISPATCHED, journal.records.last().state)
        assertTrue(!journal.unresolved(action.operationKey))
    }

    @Test fun stoppingAfterDispatchPersistsUncertaintyEvenWhenTheJournalMustSuspend() = runTest {
        val started = CompletableDeferred<Unit>()
        var uncertain = false
        val journal = object : ActionJournal {
            override suspend fun unresolved(operationKey: String) = uncertain
            override suspend fun begin(record: ActionRecord, approval: Approval?) = Unit
            override suspend fun update(record: ActionRecord) = Unit
            override suspend fun markUncertain(record: ActionRecord) {
                delay(10)
                uncertain = true
            }
        }
        val executor = ActionExecutor(journal) { 2_000 }
        val running = launch {
            executor.execute(action, observation, approval, { gate }, {
                started.complete(Unit)
                awaitCancellation()
            }, {
                fail("Cancelled effect must not be verified")
                emptyList()
            })
        }
        started.await()
        running.cancelAndJoin()
        assertTrue(uncertain)
        val again = executor.execute(
            action.copy(id = "retry", runId = "another-run"),
            observation,
            approval,
            { gate },
            { fail("Must not replay an uncertain effect") },
            { listOf("e") }
        )
        assertTrue(again is ExecutionResult.Blocked)
    }

    @Test fun verificationStorageFailureCannotReportSuccessOrReplayTheEffect() = runTest {
        val journal = Journal()
        var clock = 2_000L
        val executor = ActionExecutor(journal) { clock }
        var writes = 0
        val result = executor.execute(action, observation, approval, { gate }, { writes++ }, {
            clock = 2_300L
            throw java.io.IOException("Evidence unavailable")
        })
        assertEquals(ExecutionResult.Uncertain, result)
        assertEquals(ActionFailure.VERIFICATION_FAILED, journal.records.last().failure)
        assertEquals(300L, journal.records.last().elapsedMillis)
        assertTrue(journal.records.none { it.state == DispatchState.VERIFIED })
        assertTrue(
            executor.execute(action.copy(id = "retry"), observation, approval, { gate }, { writes++ }, {
                listOf("e")
            }) is ExecutionResult.Blocked
        )
        assertEquals(1, writes)
    }
}
