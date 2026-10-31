package dev.buddy.core.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

// New user input is queued separately from model/tool data. Closing and accepting input are atomic.
class AgentInbox {
    private val lock = Any()
    private val pending = ArrayDeque<String>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var open = true
    private var pause = false
    internal val pauseRequested: Boolean get() = synchronized(lock) { pause }
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

    fun requestPause(): Boolean = synchronized(lock) {
        if (!open || pause) return@synchronized false
        pause = true
        signal.trySend(Unit)
        true
    }

    internal fun drain(): List<String> = synchronized(lock) {
        pending.toList().also {
            pending.clear()
            while (signal.tryReceive().isSuccess) {
                // Input is drained here; a pause request stays latched until the run closes.
            }
        }
    }

    internal fun canDispatch(): Boolean = synchronized(lock) { open && !pause && pending.isEmpty() }

    internal fun closeIfEmpty(): Boolean = synchronized(lock) {
        if (pause || pending.isNotEmpty()) return@synchronized false
        open = false
        true
    }

    internal fun closeAndDrain(): List<String> = synchronized(lock) {
        open = false
        drain()
    }
}
