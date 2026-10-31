package dev.buddy

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.connection.PhoneConnection
import dev.buddy.core.ChatMessage
import dev.buddy.core.Conversation
import dev.buddy.core.MemoryPolicy
import dev.buddy.core.ModelConfig
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryControlsDeviceTest {
    @Test(timeout = 30_000)
    fun onlyUserMessagesCanBeRememberedAndControlsWorkWithoutAModelConnection() = runBlocking {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true" &&
                android.os.Build.HARDWARE == "ranchu" && android.os.Build.FINGERPRINT.contains("sdk_gphone")
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as BuddyApplication).store
        val models = ViewModelStore()
        store.clear()
        try {
            val conversation = Conversation(
                UUID.randomUUID().toString(),
                "Synthetic preferences",
                listOf(
                    ChatMessage("user", "Prefer concise replies"),
                    ChatMessage("assistant", "An inferred preference")
                )
            )
            store.put("conversation:${conversation.id}", "conversation", Json.encodeToString(conversation))
            val model = withContext(Dispatchers.Main) {
                BuddyViewModel(store, PhoneConnection(context)).also { models.put("memory", it) }
            }
            withTimeout(5_000) {
                model.state.first { it.loaded && it.history.any { row -> row.id == conversation.id } }
            }
            withTimeout(5_000) { model.memory.state.first { it.loaded } }
            assertFalse(model.memory.state.value.enabled)
            withContext(Dispatchers.Main) {
                model.openConversation(conversation.id)
                model.memory.remember(1)
            }
            assertEquals(null, model.memory.state.value.editor)
            withContext(Dispatchers.Main) { model.memory.remember(0) }
            assertEquals("Prefer concise replies", model.memory.state.value.editor?.fact)
            withContext(Dispatchers.Main) { model.memory.save("Prefer concise replies") }
            val state = withTimeout(5_000) {
                model.memory.state.first { !it.busy && it.memories.size == 1 && it.enabled }
            }
            val memory = state.memories.single()
            assertEquals(MemoryPolicy.sourceMessage(conversation.id, 0), memory.sourceMessageId)
            assertFalse(model.state.value.configured)
            assertEquals(2, model.state.value.conversation.messages.size)
            // Loading a destination alone makes no model request; previews and memory remain local.
            store.put(
                "model-config",
                "secret",
                Json.encodeToString(ModelConfig("https://memory-fixture.invalid/v1", "fixture", ""))
            )
            val sharing = withContext(Dispatchers.Main) {
                BuddyViewModel(store, PhoneConnection(context)).also { models.put("sharing", it) }
            }
            withTimeout(5_000) { sharing.state.first { it.loaded && it.configured } }
            withTimeout(5_000) { sharing.memory.state.first { it.loaded && it.enabled } }
            withContext(Dispatchers.Main) {
                sharing.sources.memory(memory.id)
                assertTrue(sharing.sources.state.value.preparing)
                sharing.setMemoryEnabled(false)
            }
            withTimeout(5_000) { sharing.memory.state.first { !it.busy && !it.enabled } }
            assertFalse(sharing.sources.state.value.preparing)
            assertEquals(null, sharing.sources.state.value.pending)
            assertTrue(sharing.state.value.draftSources.isEmpty())
            assertTrue(store.memories.get(memory.id) != null)
            withContext(Dispatchers.Main) { sharing.deleteMemory(memory.id) }
            withTimeout(5_000) { sharing.memory.state.first { !it.busy && it.memories.isEmpty() } }
            assertEquals(null, store.memories.get(memory.id))
        } finally {
            withContext(Dispatchers.Main) { models.clear() }
            store.clear()
        }
    }
}
