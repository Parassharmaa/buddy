package dev.buddy.pairing

import java.io.Closeable

class NativeSpake2(client: Boolean = true) : Closeable {
    private var handle = create(client)
    init {
        check(handle != 0L) { "Pairing context unavailable" }
    }

    @Synchronized fun message(password: ByteArray): ByteArray {
        check(handle != 0L)
        require(password.size in 1..1024)
        return checkNotNull(generate(handle, password)) { "Pairing message failed" }
    }

    @Synchronized fun process(peer: ByteArray): ByteArray {
        check(handle != 0L)
        require(peer.size == 32)
        return checkNotNull(process(handle, peer)) { "Pairing exchange failed" }
    }

    @Synchronized override fun close() {
        if (handle != 0L) {
            destroy(handle)
            handle = 0
        }
    }
    private external fun create(client: Boolean): Long
    private external fun generate(handle: Long, password: ByteArray): ByteArray?
    private external fun process(handle: Long, peer: ByteArray): ByteArray?
    private external fun destroy(handle: Long)
    companion object {
        init {
            System.loadLibrary("buddy_pairing")
        }
    }
}
