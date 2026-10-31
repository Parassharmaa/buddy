package dev.buddy.core

import dev.buddy.core.agent.SessionSupervisor
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSupervisorTest {
    @Test fun shutdownInvalidatesAdmissionAndJoinsCleanupBeforeMutatingData() = runBlocking {
        withTimeout(5_000) {
            val supervisor = SessionSupervisor()
            val entered = CompletableDeferred<SessionSupervisor.Permit>()
            val cleaning = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val mutated = CompletableDeferred<Unit>()
            launch {
                supervisor.supervise { permit ->
                    entered.complete(permit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            finish.await()
                        }
                    }
                }
            }
            val permit = entered.await()
            val shutdown = async { supervisor.quiesce { mutated.complete(Unit) } }
            cleaning.await()
            try {
                assertFalse(permit.isCurrent)
                assertFalse(mutated.isCompleted)
                var rejected = false
                try {
                    supervisor.supervise { error("Admission must remain closed") }
                } catch (_: IllegalStateException) {
                    rejected = true
                }
                assertTrue(rejected)
            } finally {
                finish.complete(Unit)
            }
            shutdown.await()
            assertTrue(mutated.isCompleted)
            supervisor.supervise { assertTrue(it.isCurrent) }
        }
    }

    @Test fun failedMutationReleasesAdmissionAndSessionChildrenCannotSelfJoin() = runBlocking {
        withTimeout(5_000) {
            val supervisor = SessionSupervisor()
            try {
                supervisor.quiesce { throw IOException("Synthetic mutation failure") }
            } catch (_: IOException) {
                // Admission must be restored after the mutation fails.
            }
            supervisor.supervise { permit ->
                var rejected = false
                try {
                    withContext(Dispatchers.Default) { supervisor.quiesce {} }
                } catch (_: IllegalStateException) {
                    rejected = true
                }
                assertTrue(rejected)
                assertTrue(permit.isCurrent)
            }
        }
    }
}
