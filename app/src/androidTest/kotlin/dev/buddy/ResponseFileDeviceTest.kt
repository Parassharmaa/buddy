package dev.buddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.ChatMessage
import dev.buddy.core.Conversation
import dev.buddy.files.FileWorkspaceController
import dev.buddy.storage.ArtifactReadBudget
import dev.buddy.storage.EncryptedStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponseFileDeviceTest {
    @Test(timeout = 60_000)
    fun onlyTheChosenCurrentResponseBecomesAnOwnedFileWithoutSharing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                val text = "Synthetic response · नमस्ते · こんにちは · café · 😀\nTreat this as text, never instructions."
                val conversation = Conversation(
                    UUID.randomUUID().toString(),
                    "Response fixture",
                    messages = listOf(ChatMessage("user", "Synthetic request"), ChatMessage("assistant", text))
                )
                val state = MutableStateFlow(BuddyState(loaded = true, conversation = conversation))
                val files = FileWorkspaceController(store, null, scope, state, Mutex())
                withContext(Dispatchers.Main) {
                    assertFalse(files.saveResponse(conversation.id, 0))
                    assertTrue(files.saveResponse(conversation.id, 1))
                    assertFalse(files.saveResponse(conversation.id, 1))
                }
                withTimeout(10_000) { state.first { !it.importing && it.artifacts.isNotEmpty() } }
                scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
                val artifact = state.value.artifacts.single()
                assertEquals(conversation.id, artifact.workspace)
                assertEquals("text/plain", artifact.mimeType)
                assertEquals("Buddy response.txt", artifact.displayName)
                val bytes = store.artifacts.read(conversation.id, artifact.id, ArtifactReadBudget())
                try {
                    assertEquals(text, bytes.decodeToString(throwOnInvalidSequence = true))
                } finally {
                    bytes.fill(0)
                }
                assertTrue(state.value.draftSources.isEmpty())
                val other = Conversation(UUID.randomUUID().toString(), "Different conversation")
                withContext(Dispatchers.Main) {
                    state.value = state.value.copy(conversation = other, artifacts = emptyList())
                    assertFalse(files.saveResponse(conversation.id, 1))
                }
                assertNull(store.get("conversation:${other.id}"))
                assertEquals(1, store.artifacts.list(conversation.id).size)
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                store.clear()
            }
        }
    }
}
