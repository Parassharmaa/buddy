package dev.buddy.core.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

// New user input is queued separately from model/tool data. Closing and accepting input are atomic.
class AgentInbox {
    private val lock = Any()
    private val pending = ArrayDeque<String>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var open = true
    internal val updates: ReceiveChannel<Unit> get() = signal

    fun offer(text: String): Boolean {
        require(text.isNotBlank() && text.length <= 8192)
        return synchronized(lock) {
            if (!open || pending.size >= 8) return@synchronized false
            pending += text
            signal.trySend(Unit)
            true
        }
    }

    internal fun drain(): List<String> = synchronized(lock) {
        pending.toList().also {
            pending.clear()
            while (signal.tryReceive().isSuccess) {
                // A drain consumes every queued wake-up together with the corresponding input.
            }
        }
    }

    internal fun canDispatch(): Boolean = synchronized(lock) { open && pending.isEmpty() }

    internal fun closeIfEmpty(): Boolean = synchronized(lock) {
        if (pending.isNotEmpty()) return@synchronized false
        open = false
        true
    }

    internal fun closeAndDrain(): List<String> = synchronized(lock) {
        open = false
        drain()
    }
}
