package dev.buddy.core

import com.sun.net.httpserver.HttpServer
import dev.buddy.core.scheduling.StatusAddress
import dev.buddy.core.scheduling.WebsiteStatusClient
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteStatusClientTest {
    @Test fun checksUseHeadWithoutSessionsCredentialsOrFollowingRedirects() = runBlocking {
        val site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val foreign = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val foreignRequests = AtomicInteger()
        foreign.createContext("/") { exchange ->
            foreignRequests.incrementAndGet()
            exchange.close()
        }
        site.createContext("/") { exchange ->
            assertEquals("HEAD", exchange.requestMethod)
            assertEquals(null, exchange.requestHeaders.getFirst("Authorization"))
            assertEquals(null, exchange.requestHeaders.getFirst("Cookie"))
            exchange.responseHeaders.add("Set-Cookie", "session=synthetic")
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${foreign.address.port}/other")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        site.start()
        foreign.start()
        try {
            WebsiteStatusClient().use { client ->
                val address = StatusAddress.parse("http://127.0.0.1:${site.address.port}/", true)
                repeat(2) { assertEquals(302, client.check(address) { true }) }
                assertEquals(0, foreignRequests.get())
            }
        } finally {
            site.stop(0)
            foreign.stop(0)
        }
    }

    @Test fun admissionFailureMakesNoRequestAndCancellationStopsAStalledCheck() = runBlocking {
        val site = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        site.createContext("/") { exchange ->
            requests.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        site.start()
        try {
            WebsiteStatusClient().use { client ->
                val address = StatusAddress.parse("http://127.0.0.1:${site.address.port}/", true)
                assertTrue(runCatching { client.check(address) { false } }.isFailure)
                assertEquals(0, requests.get())
                val pending = async { client.check(address) { true } }
                withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
                withTimeout(2_000) { pending.cancelAndJoin() }
                assertEquals(1, requests.get())
            }
        } finally {
            release.countDown()
            site.stop(0)
        }
    }
}
