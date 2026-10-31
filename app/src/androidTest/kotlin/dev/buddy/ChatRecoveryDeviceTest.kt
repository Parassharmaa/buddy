package dev.buddy

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.connection.PhoneConnection
import dev.buddy.core.ChatMessage
import dev.buddy.core.Conversation
import dev.buddy.core.ModelConfig
import dev.buddy.core.RunRecord
import dev.buddy.core.RunState
import dev.buddy.storage.EncryptedStore
import dev.buddy.storage.HistoryRetention
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatRecoveryDeviceTest {
    @Test fun stoppingThenOpeningANewChatPersistsTheCancelledRun() = runBlocking {
        requireDisposableEmulator()
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

    @Test fun shorteningRetentionRemovesTheSelectedExpiredConversationFromMemory() = runBlocking {
        requireDisposableEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversation =
            Conversation(
                UUID.randomUUID().toString(),
                "Expired fixture",
                listOf(ChatMessage("user", "Synthetic history"))
            )
        val recordId = "conversation:${conversation.id}"
        val models = ViewModelStore()
        EncryptedStore(context).use { store ->
            val previousRetention = store.historyRetention()
            try {
                store.setHistoryRetention(HistoryRetention.KEEP_UNTIL_DELETED)
                store.put(recordId, "conversation", Json.encodeToString(conversation))
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath("buddy.db").path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE
                )
                    .use { database ->
                        database.execSQL(
                            "UPDATE records SET updatedAt = ? WHERE id = ?",
                            arrayOf<Any>(System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000, recordId)
                        )
                    }
                val model = withContext(Dispatchers.Main) {
                    BuddyViewModel(store, PhoneConnection(context)).also { models.put("chat", it) }
                }
                withTimeout(5_000) {
                    model.state.first {
                        it.loaded &&
                            it.history.any { row -> row.id == conversation.id }
                    }
                }
                withContext(Dispatchers.Main) {
                    model.openConversation(conversation.id)
                    assertEquals(conversation.id, model.state.value.conversation.id)
                    model.setHistoryRetention(HistoryRetention.SEVEN_DAYS)
                }
                val result = withTimeout(5_000) {
                    model.state.first {
                        it.retention == HistoryRetention.SEVEN_DAYS &&
                            it.history.none { row -> row.id == conversation.id } &&
                            it.conversation.id != conversation.id
                    }
                }
                assertEquals(emptyList<ChatMessage>(), result.conversation.messages)
                assertNull(store.get(recordId))
            } finally {
                withContext(Dispatchers.Main) { models.clear() }
                store.remove(recordId)
                store.setHistoryRetention(previousRetention)
            }
        }
    }

    @Test fun deletingRecoveredHistoryCannotRestoreOldRowsAndKeepsObservationAlive() = runBlocking {
        requireDisposableEmulator()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = ViewModelStore()
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                val previous = (0 until 60).map { index ->
                    val id = UUID.randomUUID().toString()
                    Conversation(
                        id,
                        "Recovery fixture $index",
                        listOf(ChatMessage("user", "Synthetic recovery history")),
                        listOf(
                            RunRecord(
                                UUID.randomUUID().toString(),
                                id,
                                "Synthetic run",
                                RunState.RUNNING,
                                "Running",
                                System.currentTimeMillis()
                            )
                        )
                    )
                }
                previous.forEach { store.put("conversation:${it.id}", "conversation", Json.encodeToString(it)) }
                val oldIds = previous.map { it.id }.toSet()
                val model = withContext(Dispatchers.Main) {
                    BuddyViewModel(store, PhoneConnection(context)).also { models.put("chat", it) }
                }
                val loaded = withTimeout(10_000) { model.state.first { it.loaded && it.history.size == previous.size } }
                withContext(Dispatchers.Main) { model.deleteAll() }
                withTimeout(10_000) {
                    model.state.first {
                        it.loaded && it.conversation.id != loaded.conversation.id &&
                            it.history.isEmpty()
                    }
                }
                val resurrected = withTimeoutOrNull(1_000) {
                    model.state.first { state -> state.history.any { it.id in oldIds } }
                }
                assertNull(resurrected)
                assertTrue(store.observe("conversation").first().isEmpty())
                assertNull(store.get("model-config"))
                val fresh =
                    Conversation(
                        UUID.randomUUID().toString(),
                        "Fresh fixture",
                        listOf(ChatMessage("user", "New history"))
                    )
                store.put("conversation:${fresh.id}", "conversation", Json.encodeToString(fresh))
                withTimeout(5_000) {
                    model.state.first { state ->
                        state.history.any { it.id == fresh.id } &&
                            state.history.none { it.id in oldIds }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { models.clear() }
                store.clear()
            }
        }
    }

    private fun requireDisposableEmulator() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
    }
}
