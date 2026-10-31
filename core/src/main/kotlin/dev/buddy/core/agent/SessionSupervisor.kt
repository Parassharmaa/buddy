package dev.buddy.core.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SessionSupervisor {
    class Permit internal constructor(private val admitted: () -> Boolean) {
        val isCurrent: Boolean get() = admitted()
    }
    private class Owner(val job: Job)
    private class Tag(val owner: Owner) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Tag>
    }
    private val lock = Any()
    private val mutations = Mutex()
    private var active: Owner? = null
    private var paused = false

    suspend fun <T> supervise(operation: suspend CoroutineScope.(Permit) -> T): T = coroutineScope {
        val owner = Owner(currentCoroutineContext().job)
        synchronized(lock) {
            check(!paused && active == null) { "A session is active or supervision is paused" }
            active = owner
        }
        val permit = Permit { synchronized(lock) { !paused && active === owner && owner.job.isActive } }
        try {
            withContext(Tag(owner)) { operation(permit) }
        } finally {
            synchronized(lock) { if (active === owner) active = null }
        }
    }

    suspend fun <T> quiesce(operation: suspend () -> T): T {
        check(currentCoroutineContext()[Tag] == null) { "A session cannot join its own shutdown" }
        return withContext(NonCancellable) {
            mutations.withLock {
                val owner = synchronized(lock) {
                    paused = true
                    active
                }
                try {
                    owner?.job?.cancelAndJoin()
                    operation()
                } finally {
                    synchronized(lock) { paused = false }
                }
            }
        }
    }
}
