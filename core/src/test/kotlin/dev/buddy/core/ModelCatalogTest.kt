package dev.buddy.core

import com.sun.net.httpserver.HttpServer
import dev.buddy.core.model.ModelClient
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test fun catalogUsesExplicitEndpointWithoutToolCallsOrCredentialPersistence() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("Bearer synthetic-key", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals(-1, exchange.requestBody.read())
            val body = """{"data":[{"id":"z-model"},{"id":"日本語"},{"id":"a-model"},{"id":"a-model"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val config = ModelConfig("http://127.0.0.1:${server.address.port}/v1", "", "synthetic-key", true)
            ModelClient().use { ordinary ->
                assertTrue(runCatching { ordinary.listModels(config) }.exceptionOrNull() is IllegalArgumentException)
            }
            ModelClient(allowLoopbackHttpForTests = true).use { client ->
                assertEquals(listOf("a-model", "z-model", "日本語"), client.listModels(config))
                assertFalse(config.toolCallingVerified)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun hostileOrOversizedCatalogAndRedirectsFailWithoutEchoingProviderData() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = mapOf(
            "oversized" to ByteArray(512 * 1024 + 1) { 65 },
            "invalid" to "private-provider-canary".toByteArray(),
            "id" to """{"data":[{"id":"bad\u202Eid"}]}""".toByteArray()
        )
        bodies.forEach { (path, body) ->
            server.createContext("/$path/models") { exchange ->
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.createContext("/redirect/models") { exchange ->
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/invalid/models")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            ModelClient(allowLoopbackHttpForTests = true).use { client ->
                for (path in bodies.keys + "redirect") {
                    val failure = runCatching {
                        client.listModels(ModelConfig("http://127.0.0.1:${server.address.port}/$path", "", "", true))
                    }.exceptionOrNull() as ModelFailure
                    assertEquals(if (path == "redirect") "endpoint" else "protocol", failure.category)
                    assertFalse(failure.message.contains("private-provider-canary"))
                }
            }
        } finally {
            server.stop(0)
        }
    }
}
