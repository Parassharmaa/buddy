package dev.buddy.core

import com.sun.net.httpserver.HttpServer
import dev.buddy.core.model.ModelClient
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelClientTest {
    @Test fun ordinaryClientsRejectHttpBeforeSendingCredentialsEvenWithSavedConsent() = runBlocking {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            ModelClient().use { client ->
                val failure = runCatching {
                    client.complete(
                        ModelConfig("http://127.0.0.1:${server.address.port}/v1", "fixture", "synthetic-key", true),
                        emptyList()
                    )
                }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertEquals(0, requests.get())
            }
            assertTrue(runCatching { ModelEndpoint.forModel("http://192.168.1.2/v1", true) }.isFailure)
            assertTrue(runCatching { ModelEndpoint.forModel("http://localhost/v1", true) }.isFailure)
            assertEquals("https", ModelEndpoint.forModel("https://192.168.1.2/v1").uri.scheme)
            assertEquals("http", ModelEndpoint.forModel("http://[::1]/v1", true).uri.scheme)
        } finally {
            server.stop(0)
        }
    }

    @Test fun invalidProviderDataIsAProtocolFailureWithoutEchoingPrivateResponseText() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            val body = "not-json-private-provider-fixture".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            ModelClient(allowLoopbackHttpForTests = true).use { client ->
                val failure = runCatching {
                    client.complete(
                        ModelConfig("http://127.0.0.1:${server.address.port}/v1", "fixture", "", true),
                        emptyList()
                    )
                }.exceptionOrNull() as ModelFailure
                assertEquals("protocol", failure.category)
                assertFalse(failure.message.contains("private-provider-fixture"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun realHttpRoundTripUsesConfiguredEndpointAndMatchesToolResult() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = mutableListOf<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            assertEquals("Bearer synthetic-fixture-key", exchange.requestHeaders.getFirst("Authorization"))
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            synchronized(bodies) { bodies += body }
            val reply = if (body.contains("tool_call_id")) {
                """{"choices":[{"message":{"content":"Verified"},"finish_reason":"stop"}]}"""
            } else {
                """{"choices":[{"message":{"tool_calls":[{"id":"probe-1","function":{"name":"buddy_probe","arguments":"{\"echo\":\"ready\"}"}}]},"finish_reason":"tool_calls"}]}"""
            }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(reply.toByteArray()) }
        }
        server.start()
        try {
            ModelClient(allowLoopbackHttpForTests = true).use { client ->
                val config =
                    ModelConfig("http://127.0.0.1:${server.address.port}/v1", "fixture", "synthetic-fixture-key", true)
                assertEquals("Tool calling and response verified", client.probe(config))
                assertTrue(bodies.last().contains("\"tool_call_id\":\"probe-1\""))
                val messages = Json.parseToJsonElement(bodies.last()).jsonObject.getValue("messages").jsonArray
                val assistant = messages.first {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
                }.jsonObject
                val call = assistant.getValue("tool_calls").jsonArray.single().jsonObject
                assertEquals("function", call.getValue("type").jsonPrimitive.content)
                assertFalse(config.toString().contains("synthetic-fixture-key"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun streamingAndAuthenticationFailureAreHandled() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val body = listOf(
                """data: {"choices":[{"delta":{"content":"Hello"}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                "data: [DONE]"
            ).joinToString("\n\n", postfix = "\n\n")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/denied/chat/completions") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            ModelClient(allowLoopbackHttpForTests = true).use { client ->
                val config = ModelConfig("http://127.0.0.1:${server.address.port}/v1", "fixture", "", true)
                assertEquals("Hello", client.complete(config, listOf(ChatMessage("user", "Hello"))).message.content)
                val failure = runCatching {
                    client.complete(
                        config.copy(baseUrl = "http://127.0.0.1:${server.address.port}/denied"),
                        emptyList()
                    )
                }.exceptionOrNull()
                assertEquals("authentication", (failure as ModelFailure).category)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun stopCancelsAStalledModelWithoutWaitingForItsResponse() = runBlocking {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.close()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("data: ".toByteArray())
            exchange.responseBody.flush()
            requested.countDown()
            release.await(3, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        try {
            ModelClient(allowLoopbackHttpForTests = true).use { client ->
                val request = async(Dispatchers.Default) {
                    client.complete(
                        ModelConfig("http://127.0.0.1:${server.address.port}/v1", "fixture", "", true),
                        emptyList()
                    )
                }
                assertTrue(withContext(Dispatchers.IO) { requested.await(2, TimeUnit.SECONDS) })
                val start = System.nanoTime()
                withTimeout(1_000) { request.cancelAndJoin() }
                assertTrue("Cancellation waited for the provider", (System.nanoTime() - start) / 1_000_000 < 500)
            }
        } finally {
            release.countDown()
            server.stop(0)
        }
    }
}
