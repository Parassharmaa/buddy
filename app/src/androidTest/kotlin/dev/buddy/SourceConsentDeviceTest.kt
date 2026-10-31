package dev.buddy

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.connection.PhoneConnection
import dev.buddy.core.ChatSourceKind
import dev.buddy.core.Conversation
import dev.buddy.storage.EncryptedStore
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceConsentDeviceTest {
    private class ModelFixture : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor()
        val base = "http://127.0.0.1:${socket.localPort}/v1"
        val requests = CopyOnWriteArrayList<JsonObject>()
        init {
            worker.submit {
                while (!socket.isClosed) {
                    runCatching {
                        socket.accept().use { client ->
                            client.soTimeout = 5_000
                            val input = client.getInputStream().buffered()
                            fun line(): String {
                                val bytes = ByteArrayOutputStream()
                                while (true) {
                                    val next = input.read()
                                    check(next >= 0 && bytes.size() < 8192)
                                    if (next == 10) return bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r')
                                    bytes.write(next)
                                }
                            }
                            check(line().startsWith("POST /v1/chat/completions "))
                            var length = 0
                            var headerBytes = 0
                            while (true) {
                                val header = line()
                                if (header.isEmpty()) break
                                headerBytes += header.length
                                check(headerBytes < 16384)
                                if (header.startsWith("content-length:", true)) {
                                    length =
                                        header.substringAfter(':').trim().toInt()
                                }
                            }
                            check(length in 1..2 * 1024 * 1024)
                            val body = Json.parseToJsonElement(input.readNBytes(length).decodeToString()).jsonObject
                            requests += body
                            val reply = if (body["tools"] != null && body.getValue("messages").jsonArray.none {
                                    it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
                                }
                            ) {
                                """{"choices":[{"message":{"tool_calls":[{"id":"probe","function":{"name":"buddy_probe","arguments":"{\"echo\":\"ready\"}"}}]},"finish_reason":"tool_calls"}]}"""
                            } else {
                                """{"choices":[{"message":{"content":"Fixture summary."},"finish_reason":"stop"}]}"""
                            }
                            val bytes = reply.toByteArray()
                            client.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray()
                                )
                                write(bytes)
                                flush()
                            }
                        }
                    }
                }
            }
        }
        override fun close() {
            socket.close()
            worker.shutdownNow()
        }
    }

    private suspend fun loadedModel(store: EncryptedStore, models: ViewModelStore): BuddyViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = withContext(Dispatchers.Main) {
            BuddyViewModel(store, PhoneConnection(context)).also { models.put("sources", it) }
        }
        withTimeout(5_000) { model.state.first { it.loaded } }
        return model
    }

    @Test(timeout = 45_000)
    fun selectedTextStaysLocalUntilSendAndHistoryCannotBeForwardedToAnotherModel() = runBlocking {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true" &&
                android.os.Build.HARDWARE == "ranchu" && android.os.Build.FINGERPRINT.contains("sdk_gphone")
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as BuddyApplication).store
        val models = ViewModelStore()
        store.clear()
        try {
            ModelFixture().use { server ->
                val model = loadedModel(store, models)
                withContext(Dispatchers.Main) { model.saveModel(server.base, "fixture-model", "synthetic", true) }
                withTimeout(10_000) { model.state.first { !it.testingModel && it.modelStatus.contains("verified") } }
                assertEquals(2, server.requests.size)
                assertTrue(
                    Json.decodeFromString<dev.buddy.core.ModelConfig>(
                        requireNotNull(store.get("model-config"))
                    ).toolCallingVerified
                )
                val conversation = model.state.value.conversation
                store.put("conversation:${conversation.id}", "conversation", Json.encodeToString(conversation))
                val text = "Synthetic selected reference · café · こんにちは"
                val artifact = store.artifacts.importFile(
                    conversation.id,
                    "reference.txt",
                    "text/plain",
                    Uri.parse("content://fixture/reference"),
                    text.toByteArray()
                )
                withTimeout(5_000) { model.state.first { it.history.any { row -> row.id == conversation.id } } }
                withContext(Dispatchers.Main) { model.openConversation(conversation.id) }
                withTimeout(5_000) { model.state.first { it.artifacts.any { file -> file.id == artifact.id } } }
                withContext(Dispatchers.Main) { model.sources.file(artifact.id) }
                withTimeout(5_000) { model.sources.state.first { it.pending != null } }
                assertTrue(model.state.value.draftSources.isEmpty())
                assertEquals(2, server.requests.size)
                withContext(Dispatchers.Main) { model.sources.cancel() }
                assertEquals(2, server.requests.size)
                withContext(Dispatchers.Main) { model.sources.file(artifact.id) }
                withTimeout(5_000) { model.sources.state.first { it.pending != null } }
                withContext(Dispatchers.Main) { assertTrue(model.sources.confirm()) }
                assertEquals(ChatSourceKind.FILE, model.state.value.draftSources.single().kind)
                assertEquals(2, server.requests.size)
                withContext(Dispatchers.Main) {
                    model.draft("Summarize the selected reference")
                    model.send()
                }
                withTimeout(10_000) {
                    model.state.first {
                        !it.active &&
                            it.conversation.messages.lastOrNull()?.role == "assistant"
                    }
                }
                assertEquals(3, server.requests.size)
                val wire = server.requests.last().getValue("messages").jsonArray.last().jsonObject
                assertFalse(wire.containsKey("sources"))
                val content = wire.getValue("content").jsonPrimitive.content
                assertTrue(content.contains(text) && content.contains("untrusted data"))
                withTimeout(5_000) {
                    while (Json.decodeFromString<Conversation>(
                            requireNotNull(store.get("conversation:${conversation.id}"))
                        )
                            .messages.none { it.sources.isNotEmpty() }
                    ) {
                        kotlinx.coroutines.delay(20)
                    }
                }
                withContext(Dispatchers.Main) { model.saveModel(server.base, "another-model", "", true) }
                withTimeout(10_000) { model.state.first { !it.testingModel && it.modelName == "another-model" } }
                assertEquals(5, server.requests.size)
                withContext(Dispatchers.Main) {
                    model.draft("Follow up")
                    model.send()
                }
                assertFalse(model.state.value.active)
                assertTrue(model.state.value.error?.contains("another provider or model") == true)
                assertEquals(5, server.requests.size)
            }
        } finally {
            withContext(Dispatchers.Main) { models.clear() }
            store.clear()
        }
    }
}
