package dev.buddy.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeSpake2Test {
    @Test fun nativeBoundaryAndCleanup() {
        val alice = NativeSpake2(true)
        val bob = NativeSpake2(false)
        try {
            val a = alice.message("synthetic-password".toByteArray())
            val b = bob.message("synthetic-password".toByteArray())
            assertFalse(a.contentEquals(b))
            assertArrayEquals(alice.process(b), bob.process(a))
            assertThrows(IllegalStateException::class.java) { alice.process(b) }
        } finally {
            alice.close()
            bob.close()
            alice.close()
            bob.close()
        }
        assertThrows(IllegalStateException::class.java) { alice.message(byteArrayOf(1)) }
    }

    @Test fun nativeStateAndBoundsErrorsPropagate() {
        NativeSpake2().use { context ->
            assertThrows(IllegalStateException::class.java) { context.process(ByteArray(32)) }
        }
        NativeSpake2().use { context ->
            context.message("synthetic-password".toByteArray())
            assertThrows(IllegalArgumentException::class.java) { context.process(ByteArray(31)) }
        }
    }
}
