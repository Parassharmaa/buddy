package dev.buddy.pairing

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test fun invalidAndStaleNativeTokensNeverDereferenceReleasedContexts() {
        val generate = NativeSpake2::class.java.getDeclaredMethod(
            "generate",
            Long::class.javaPrimitiveType,
            ByteArray::class.java
        )
            .apply { isAccessible = true }
        val process = NativeSpake2::class.java.getDeclaredMethod(
            "process",
            Long::class.javaPrimitiveType,
            ByteArray::class.java
        )
            .apply { isAccessible = true }
        val token = NativeSpake2::class.java.getDeclaredField("handle").apply { isAccessible = true }
        val context = NativeSpake2()
        val stale = token.getLong(context)
        context.close()
        NativeSpake2().use { current ->
            assertNotEquals(stale, token.getLong(current))
            for (invalid in listOf(0L, -1L, Long.MAX_VALUE, stale)) {
                val failure = assertThrows(InvocationTargetException::class.java) {
                    generate.invoke(current, invalid, byteArrayOf(1))
                }
                assertTrue(failure.cause is IllegalStateException)
                val exchange = assertThrows(InvocationTargetException::class.java) {
                    process.invoke(current, invalid, ByteArray(32))
                }
                assertTrue(exchange.cause is IllegalStateException)
            }
        }
    }

    @Test fun nativeContextCapacityIsBoundedAndRecoversAfterClose() {
        val contexts = mutableListOf<NativeSpake2>()
        try {
            repeat(128) { contexts += NativeSpake2() }
            assertThrows(IllegalStateException::class.java) { NativeSpake2() }
        } finally {
            contexts.forEach { it.close() }
        }
        NativeSpake2().use { assertTrue(it.message(byteArrayOf(1)).isNotEmpty()) }
    }
}
