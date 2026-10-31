package dev.buddy

import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.connection.PhoneConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionShutdownDeviceTest {
    @Test(timeout = 30_000)
    fun clearWaitsForEngineeringCleanupBeforeErasingEncryptedData() = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true" &&
                Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone")
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as BuddyApplication).store
        val models = ViewModelStore()
        val entered = CompletableDeferred<dev.buddy.core.agent.SessionSupervisor.Permit>()
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.clear()
        val session = launch {
            EngineeringSessions.supervisor.supervise { permit ->
                entered.complete(permit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        release.await()
                    }
                }
            }
        }
        try {
            withTimeout(10_000) {
                val permit = entered.await()
                store.put("shutdown-canary", "test", "Synthetic private value")
                val model = withContext(Dispatchers.Main) {
                    BuddyViewModel(store, PhoneConnection(context)).also { models.put("shutdown", it) }
                }
                model.state.first { it.loaded }
                withContext(Dispatchers.Main) { model.deleteAll() }
                cleaning.await()
                assertFalse(permit.isCurrent)
                assertTrue(model.state.value.clearingData)
                assertEquals("Synthetic private value", store.get("shutdown-canary"))
                release.complete(Unit)
                session.join()
                model.state.first { it.loaded && !it.clearingData }
                assertNull(store.get("shutdown-canary"))
                EngineeringSessions.supervisor.supervise { assertTrue(it.isCurrent) }
            }
        } finally {
            release.complete(Unit)
            withContext(NonCancellable) {
                session.cancel()
                session.join()
                EngineeringSessions.supervisor.quiesce {}
                withContext(Dispatchers.Main) { models.clear() }
                store.clear()
            }
        }
    }
}
