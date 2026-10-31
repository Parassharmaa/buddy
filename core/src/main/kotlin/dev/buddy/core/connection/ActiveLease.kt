package dev.buddy.core.connection

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Maintains one active lease; every renewal requires a new daemon authorization proof. */
class ActiveLease(
    initialDeadline: Long,
    private val now: () -> Long,
    private val refreshAuthorization: suspend () -> Long,
    private val renewLease: suspend () -> Long,
    private val release: suspend () -> Unit
) {
    private val started = AtomicBoolean()
    private val stopped = AtomicBoolean()

    @Volatile var deadline: Long = initialDeadline
        private set

    val isCurrent: Boolean get() = !stopped.get() && deadline - now() in 1..MAX_FRESHNESS_MS

    suspend fun maintain() {
        check(started.compareAndSet(false, true)) { "Lease maintenance already started" }
        try {
            check(isCurrent) { "Lease expired" }
            while (true) {
                // Start early when inheriting a lease whose original proof is already aging.
                delay(minOf(RENEW_INTERVAL_MS, maxOf(0, deadline - now() - PROOF_BUDGET_MS)))
                check(isCurrent) { "Lease expired" }
                val previousDeadline = deadline
                val nextDeadline = withTimeout(minOf(PROOF_BUDGET_MS, previousDeadline - now())) {
                    val authorization = refreshAuthorization()
                    currentCoroutineContext().ensureActive()
                    check(isCurrent && authorization > previousDeadline)
                    check(authorization - now() in 1..MAX_FRESHNESS_MS)
                    val lease = renewLease()
                    currentCoroutineContext().ensureActive()
                    check(isCurrent && lease - now() in 1..MAX_FRESHNESS_MS)
                    minOf(authorization, lease)
                }
                check(isCurrent && nextDeadline > previousDeadline)
                deadline = nextDeadline
            }
        } finally {
            // Stop admitting actions before cancellation-safe resource cleanup starts.
            stopped.set(true)
            withContext(NonCancellable) { release() }
        }
    }

    companion object {
        const val MAX_FRESHNESS_MS = 30_000L
        private const val RENEW_INTERVAL_MS = 10_000L
        private const val PROOF_BUDGET_MS = 15_000L
    }
}
