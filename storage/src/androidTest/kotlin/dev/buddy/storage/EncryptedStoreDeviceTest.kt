package dev.buddy.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.ActionProposal
import dev.buddy.core.ActionRecord
import dev.buddy.core.Approval
import dev.buddy.core.DispatchState
import dev.buddy.core.Effect
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedStoreDeviceTest {
    @Test fun secretRoundTripAndDeletionUseTheRealAndroidKeystore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secret = "synthetic-sensitive-fixture-" + UUID.randomUUID()
        EncryptedStore(context).use { store ->
            store.clear()
            store.put("fixture-secret", "secret", secret)
            assertEquals(secret, store.get("fixture-secret"))
            for (suffix in listOf("", "-wal")) {
                val file = java.io.File(context.getDatabasePath("buddy.db").path + suffix)
                if (file.exists()) assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
            }
            store.clear()
            assertNull(store.get("fixture-secret"))
        }
    }

    @Test fun uncertainEffectsAndConsumedConsentSurviveReopeningTheDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = ActionProposal(
            "fixture-action", "fixture-run", "o1", 1, "fixture", Effect.SEND,
            "Alice", "Synthetic fixture message", "fixture inbox", "f1"
        )
        val approval =
            Approval("fixture-approval", action.runId, action.operationKey, "f1", Long.MAX_VALUE, accepted = true)
        EncryptedStore(context).use { store ->
            store.clear()
            store.begin(ActionRecord(action, DispatchState.DISPATCHING), approval)
        }
        EncryptedStore(context).use { store ->
            assertTrue(store.unresolved(action.copy(id = "new-id", runId = "new-run").operationKey))
            assertEquals("consumed", store.get("consumed:fixture-approval"))
            store.update(ActionRecord(action, DispatchState.NOT_DISPATCHED))
            assertFalse(store.unresolved(action.operationKey))
            store.clear()
        }
    }
}
