package dev.buddy

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.ChatMessage
import dev.buddy.core.ChatSource
import dev.buddy.core.ChatSourceKind
import dev.buddy.core.Conversation
import dev.buddy.core.ConversationExport
import dev.buddy.core.ModelConfig
import dev.buddy.core.SourceContext
import dev.buddy.files.DocumentAccess
import dev.buddy.files.FileWorkspaceController
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationExportDeviceTest {
    @Test(timeout = 30_000)
    fun selectedConversationExportsSourcesWithoutCredentialsAndAStalePickerCannotOverwriteIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val destination =
            requireNotNull(
                resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "buddy-conversation-fixture.json")
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            )
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                val config = ModelConfig("https://export-fixture.invalid/v1", "fixture", "Synthetic credential canary")
                store.put("model-config", "secret", Json.encodeToString(config))
                val reference = ChatSource(
                    "selected-source",
                    ChatSourceKind.FILE,
                    "reference",
                    "Quoted fixture · café · こんにちは",
                    false,
                    SourceContext.destination(config)
                )
                val conversation = Conversation(
                    UUID.randomUUID().toString(),
                    "Selected fixture",
                    listOf(ChatMessage("user", "Summarize this reference", sources = listOf(reference)))
                )
                val state = MutableStateFlow(BuddyState(loaded = true, conversation = conversation))
                val files = FileWorkspaceController(store, DocumentAccess(context), scope, state, Mutex())
                withContext(Dispatchers.Main) { files.exportConversation(conversation.id, destination) }
                withTimeout(10_000) { state.first { !it.exporting } }
                scope.coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
                assertEquals(null, state.value.error)
                resolver.update(
                    destination,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                val exported = requireNotNull(resolver.openInputStream(destination)).use { it.readNBytes(1024 * 1024) }
                try {
                    val decoded = Json.decodeFromString<ConversationExport>(exported.decodeToString())
                    assertEquals(conversation, decoded.conversation)
                    assertFalse(exported.decodeToString().contains(config.apiKey))
                    withContext(Dispatchers.Main) {
                        state.value =
                            BuddyState(
                                loaded = true,
                                conversation = Conversation(UUID.randomUUID().toString(), "Other fixture")
                            )
                        files.exportConversation(conversation.id, destination)
                    }
                    assertFalse(state.value.exporting)
                    assertTrue(state.value.error.orEmpty().contains("original conversation"))
                    val after = requireNotNull(resolver.openInputStream(destination)).use { it.readNBytes(1024 * 1024) }
                    try {
                        assertArrayEquals(exported, after)
                    } finally {
                        after.fill(0)
                    }
                } finally {
                    exported.fill(0)
                }
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                resolver.delete(destination, null, null)
                store.clear()
            }
        }
    }
}
