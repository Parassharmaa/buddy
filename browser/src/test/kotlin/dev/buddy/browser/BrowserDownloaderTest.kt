package dev.buddy.browser

import com.sun.net.httpserver.HttpServer
import dev.buddy.core.WorkspaceFileTypes
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloaderTest {
    private fun server() = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private fun address(server: HttpServer, path: String) =
        WebAddress.parse("http://127.0.0.1:${server.address.port}$path")

    @Test fun redirectsStayOnTheApprovedOriginAndCookieScope() = runBlocking {
        val site = server()
        val foreign = server()
        val foreignRequests = AtomicInteger()
        val bytes = "%PDF-1.7 synthetic file".toByteArray()
        foreign.createContext("/") { exchange ->
            foreignRequests.incrementAndGet()
            exchange.close()
        }
        site.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "/file")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        site.createContext("/foreign") { exchange ->
            exchange.responseHeaders.add("Location", address(foreign, "/private").url)
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        val scopedCookies = AtomicInteger()
        site.createContext("/file") { exchange ->
            if (exchange.requestHeaders.getFirst("Cookie") == "session=synthetic") scopedCookies.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        site.start()
        foreign.start()
        try {
            BrowserDownloader().use { downloader ->
                val file = downloader.fetch(WebDownloadGrant(address(site, "/start"), "session=synthetic"))
                assertEquals("résumé.pdf", file.name)
                assertArrayEquals(bytes, file.bytes)
                file.bytes.fill(0)
                assertEquals(1, scopedCookies.get())
                assertTrue(
                    runCatching {
                        downloader.fetch(WebDownloadGrant(address(site, "/foreign"), "session=synthetic"))
                    }.isFailure
                )
                assertEquals(0, foreignRequests.get())
            }
        } finally {
            site.stop(0)
            foreign.stop(0)
        }
    }

    @Test fun typeFilenameAndActualChunkedByteLimitsAreEnforcedBeforeReturningAFile() = runBlocking {
        val site = server()
        site.createContext("/executable") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/vnd.android.package-archive")
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.use { it.write(0) }
        }
        site.createContext("/wrong-name") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/pdf")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=payload.apk")
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.use { it.write(0) }
        }
        site.createContext("/huge") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, 0)
            runCatching {
                exchange.responseBody.use { output ->
                    val block = ByteArray(8192) { 65 }
                    repeat(WorkspaceFileTypes.MAX_FILE_BYTES / block.size + 1) { output.write(block) }
                }
            }
            exchange.close()
        }
        site.start()
        try {
            BrowserDownloader().use { downloader ->
                for (path in listOf("/executable", "/wrong-name", "/huge")) {
                    assertTrue(runCatching { downloader.fetch(WebDownloadGrant(address(site, path), null)) }.isFailure)
                }
            }
        } finally {
            site.stop(0)
        }
    }

    @Test fun approvalsAreSingleUseAndExpireBeforeNetworkAccess() = runBlocking {
        val site = server()
        val requests = AtomicInteger()
        site.createContext("/file") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.use { it.write(65) }
        }
        site.start()
        try {
            BrowserDownloader().use { downloader ->
                val stale = WebDownloadGrant(address(site, "/file"), null, 0L) { 6_000_000_000L }
                assertTrue(runCatching { downloader.fetch(stale) }.isFailure)
                assertEquals(0, requests.get())
                val grant = WebDownloadGrant(address(site, "/file"), null)
                downloader.fetch(grant).bytes.fill(0)
                assertTrue(runCatching { downloader.fetch(grant) }.isFailure)
                assertEquals(1, requests.get())
            }
        } finally {
            site.stop(0)
        }
    }

    @Test fun cancellationStopsAResponseThatNeverFinishes() = runBlocking {
        val site = server()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        site.createContext("/stalled") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, 0)
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        site.start()
        try {
            BrowserDownloader().use { downloader ->
                val pending = async { downloader.fetch(WebDownloadGrant(address(site, "/stalled"), null)) }
                withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
                withTimeout(2_000) { pending.cancelAndJoin() }
            }
        } finally {
            release.countDown()
            site.stop(0)
        }
    }
}
