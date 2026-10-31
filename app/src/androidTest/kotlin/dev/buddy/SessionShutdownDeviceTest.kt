package dev.buddy

import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.connection.PhoneConnection
import dev.buddy.core.ModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    fun failedModelTestPreservesSavedCredentialsAndExplicitSavePersistsUnverified() = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true" &&
                Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone")
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as BuddyApplication).store
        val models = ViewModelStore()
        store.clear()
        val previous = ModelConfig(
            "https://old-model.invalid/v1",
            "old-model",
            "old-synthetic-key",
            toolCallingVerified = true
        )
        store.put("model-config", "secret", Json.encodeToString(previous))
        try {
            withTimeout(15_000) {
                val model = withContext(Dispatchers.Main) {
                    BuddyViewModel(store, PhoneConnection(context)).also { models.put("failed-model", it) }
                }
                model.state.first { it.loaded }
                assertTrue(model.state.value.modelStatus.contains("verified"))
                withContext(Dispatchers.Main) {
                    model.saveModel("https://127.0.0.1:1/v1", "new-model", "new-synthetic-key", false)
                }
                model.state.first { !it.testingModel }
                assertEquals(previous, Json.decodeFromString<ModelConfig>(requireNotNull(store.get("model-config"))))
                assertEquals("old-model", model.state.value.modelName)
                withContext(Dispatchers.Main) {
                    model.saveModel(
                        "https://127.0.0.1:1/v1",
                        "new-model",
                        "new-synthetic-key",
                        false,
                        saveUnverified = true
                    )
                }
                model.state.first { !it.testingModel }
                val saved = Json.decodeFromString<ModelConfig>(requireNotNull(store.get("model-config")))
                assertEquals("new-model", saved.model)
                assertFalse(saved.toolCallingVerified)
                val reloaded = withContext(Dispatchers.Main) {
                    BuddyViewModel(store, PhoneConnection(context)).also { models.put("reloaded-model", it) }
                }
                reloaded.state.first { it.loaded }
                assertTrue(reloaded.state.value.modelStatus.startsWith("Unverified"))
                assertEquals("new-model", reloaded.state.value.modelName)
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { models.clear() }
                store.clear()
            }
        }
    }

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

    @Test(timeout = 30_000)
    fun changedModelSettingsWaitForEngineeringCleanupBeforeReplacingTheProvider() = runBlocking {
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
        val previous = ModelConfig("https://old-model.invalid/v1", "old-model", "old-synthetic-key")
        store.put("model-config", "secret", Json.encodeToString(previous))
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
                val model = withContext(Dispatchers.Main) {
                    BuddyViewModel(store, PhoneConnection(context)).also { models.put("config-shutdown", it) }
                }
                model.state.first { it.loaded }
                withContext(Dispatchers.Main) {
                    model.saveModel(
                        "https://127.0.0.1:1/v1",
                        "new-model",
                        "new-synthetic-key",
                        false,
                        saveUnverified = true
                    )
                }
                cleaning.await()
                assertFalse(permit.isCurrent)
                assertEquals(previous, Json.decodeFromString<ModelConfig>(requireNotNull(store.get("model-config"))))
                assertEquals("old-model", model.state.value.modelName)
                release.complete(Unit)
                session.join()
                model.state.first { it.modelName == "new-model" && !it.testingModel }
                val current = Json.decodeFromString<ModelConfig>(requireNotNull(store.get("model-config")))
                assertEquals("new-model", current.model)
                assertEquals("https://127.0.0.1:1/v1", current.baseUrl)
                assertEquals("new-synthetic-key", current.apiKey)
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
