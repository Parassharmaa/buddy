package dev.buddy

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.connection.PhoneConnection
import dev.buddy.core.Conversation
import dev.buddy.core.ModelConfig
import dev.buddy.core.RunState
import dev.buddy.storage.EncryptedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRecoveryDeviceTest {
    @Test fun stoppingThenOpeningANewChatPersistsTheCancelledRun() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EncryptedStore(context).use { store ->
            val savedConfig = store.get("model-config")
            val models = ViewModelStore()
            var conversationId: String? = null
            try {
                // The model job is cancelled before its initial persistence completes: no model request is needed.
                store.put(
                    "model-config",
                    "secret",
                    Json.encodeToString(ModelConfig("https://127.0.0.1:1/v1", "fixture", ""))
                )
                val model = withContext(Dispatchers.Main) {
                    BuddyViewModel(store, PhoneConnection(context)).also { models.put("chat", it) }
                }
                withTimeout(5_000) { model.state.first { it.loaded } }
                withContext(Dispatchers.Main) {
                    model.draft("Keep the stopped run in history")
                    model.send()
                    conversationId = model.state.value.conversation.id
                    model.stop()
                    model.newChat()
                    assertFalse(model.state.value.active)
                    assertNotEquals(conversationId, model.state.value.conversation.id)
                }
                val recovered = withTimeout(5_000) {
                    var saved: Conversation?
                    do {
                        delay(20)
                        saved =
                            store.get("conversation:$conversationId")?.let { Json.decodeFromString<Conversation>(it) }
                    } while (saved?.runs?.singleOrNull()?.state != RunState.CANCELLED)
                    requireNotNull(saved)
                }
                assertEquals("Keep the stopped run in history", recovered.messages.single().content)
                assertEquals(RunState.CANCELLED, recovered.runs.single().state)
            } finally {
                withContext(Dispatchers.Main) { models.clear() }
                conversationId?.let { store.remove("conversation:$it") }
                if (savedConfig ==
                    null
                ) {
                    store.remove("model-config")
                } else {
                    store.put("model-config", "secret", savedConfig)
                }
            }
        }
    }
}
