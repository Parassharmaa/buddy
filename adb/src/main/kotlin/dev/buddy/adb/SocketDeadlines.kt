package dev.buddy.adb

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

// An inactivity timeout alone permits a peer to drip bytes indefinitely.
internal object SocketDeadlines {
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "buddy-adb-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun schedule(timeoutMs: Long, close: () -> Unit): ScheduledFuture<*> =
        timer.schedule({ close() }, timeoutMs, TimeUnit.MILLISECONDS)
}
