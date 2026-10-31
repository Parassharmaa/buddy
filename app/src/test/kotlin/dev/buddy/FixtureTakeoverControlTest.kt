package dev.buddy

import dev.buddy.qualification.FixtureTakeoverControl
import dev.buddy.qualification.FixtureTakeoverPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureTakeoverControlTest {
    @Test fun commandsAreAcceptedOnceAndOnlyAtTheirNativeBoundary() = runBlocking {
        withTimeout(5_000) {
            val control = FixtureTakeoverControl()
            assertFalse(control.request(false))
            assertFalse(control.request(true))
            val ready = CompletableDeferred<Unit>()
            val human = CompletableDeferred<Unit>()
            val task = async {
                control.awaitTakeover { if (it == FixtureTakeoverPhase.READY) ready.complete(Unit) }
                control.awaitReturn { if (it == FixtureTakeoverPhase.HUMAN) human.complete(Unit) }
            }
            ready.await()
            assertFalse(control.request(true))
            assertTrue(control.request(false))
            assertFalse(control.request(false))
            human.await()
            assertFalse(control.request(false))
            assertTrue(control.request(true))
            assertFalse(control.request(true))
            task.await()
            control.close()
            assertFalse(control.request(false))
            assertFalse(control.request(true))
        }
    }
}
