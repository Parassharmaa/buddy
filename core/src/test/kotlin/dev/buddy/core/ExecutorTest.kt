package dev.buddy.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    private class Journal : ActionJournal {
        val barriers = mutableSetOf<String>()
        val consumed = mutableSetOf<String>()
        val records = mutableListOf<ActionRecord>()
        override suspend fun unresolved(operationKey: String) = operationKey in barriers
        override suspend fun begin(record: ActionRecord, approval: Approval?) {
            check(approval == null || consumed.add(approval.id))
            records += record
            barriers += record.proposal.operationKey
        }
        override suspend fun update(record: ActionRecord) {
            records += record
            if (record.state ==
                DispatchState.VERIFIED
            ) {
                barriers.remove(record.proposal.operationKey)
            }
        }
        override suspend fun markUncertain(record: ActionRecord) {
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
}
