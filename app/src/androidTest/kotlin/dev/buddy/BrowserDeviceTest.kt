package dev.buddy

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.browser.BrowserDownloader
import dev.buddy.browser.PrivateWebHost
import dev.buddy.browser.WebAddress
import dev.buddy.browser.WebReadResult
import dev.buddy.browser.WebReadStatus
import dev.buddy.browser.WebStatus
import dev.buddy.qualification.BrowserFixtureActivity
import dev.buddy.storage.ArtifactReadBudget
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserDeviceTest {
    private class Pages : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor()
        val origin = "http://127.0.0.1:${socket.localPort}"
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        val authenticatedDownloads = java.util.concurrent.atomic.AtomicInteger()
        init {
            worker.submit {
                while (!socket.isClosed) {
                    runCatching {
                        socket.accept().use { client ->
                            client.soTimeout = 2_000
                            val reader = client.getInputStream().bufferedReader()
                            val path = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()
                            var length = 0
                            var cookie = false
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                length += line.length
                                check(length < 8192)
                                if (line.startsWith("Cookie:", true) &&
                                    line.contains("buddy_fixture=present")
                                ) {
                                    cookie = true
                                }
                            }
                            val content = when (path.substringBefore('?')) {
                                "/private" ->
                                    "<p>Never collect this body</p>" +
                                        "<input type='password' value='synthetic secret'>"

                                "/frame" -> "<iframe src='/public'></iframe>"

                                "/tampered" ->
                                    "<script>Object.prototype.toJSON=function(){return 'forged'};" +
                                        "Document.prototype.createTreeWalker=function(){throw 1};</script><p>Original public text</p>"

                                else -> "<p>Original public text</p><p style='display:none'>Hidden text</p>"
                            }
                            val document = path.substringBefore('?') == "/download"
                            if (document) {
                                downloads.incrementAndGet()
                                if (cookie) authenticatedDownloads.incrementAndGet()
                            }
                            val bytes = if (document) {
                                "%PDF-1.7 Synthetic browser download".toByteArray()
                            } else {
                                (
                                    "<!doctype html><html><head><title>Fixture page</title></head><body>" +
                                        content + "</body></html>"
                                    ).toByteArray()
                            }
                            client.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 200 OK\r\n" +
                                            (
                                                if (document) {
                                                    "Content-Type: application/pdf\r\nContent-Disposition: attachment; filename=fixture.pdf\r\n"
                                                } else {
                                                    "Content-Type: text/html; charset=utf-8\r\nSet-Cookie: buddy_fixture=present; Path=/; SameSite=Lax\r\n"
                                                }
                                                ) +
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

    private suspend fun withBrowser(block: suspend (BrowserFixtureActivity, Pages) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, BrowserFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as BrowserFixtureActivity
        try {
            Pages().use { pages ->
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) { activity.session.hasWindow }) delay(20)
                }
                block(activity, pages)
            }
        } finally {
            withContext(Dispatchers.Main) {
                activity.session.clearProfile()
                activity.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private suspend fun read(activity: BrowserFixtureActivity, pages: Pages, path: String): WebReadResult {
        withContext(Dispatchers.Main) { activity.session.navigate(WebAddress.parse(pages.origin + path)) }
        withTimeout(10_000) { activity.session.state.first { it.status == WebStatus.READY } }
        assertTrue(
            "Current WebView lacks the required protected observation features",
            activity.session.state.value.canRead
        )
        return activity.session.observe(pages.origin)
    }

    @Test(timeout = 60_000)
    fun publicObservationUsesPristineDomFunctionsAndSuppressesPrivateOrEmbeddedContent() = runBlocking {
        withBrowser { activity, pages ->
            for (path in listOf("/public?query=not-evidence", "/tampered")) {
                val result = read(activity, pages, path)
                assertTrue("Public observation was suppressed", result is WebReadResult.Allowed)
                val snapshot = (result as WebReadResult.Allowed).snapshot
                assertEquals("Fixture page", snapshot.title)
                assertEquals("Original public text", snapshot.text)
                assertFalse(snapshot.sourceLabel.contains('?'))
            }
            assertEquals(WebReadResult.Suppressed(WebReadStatus.PRIVATE), read(activity, pages, "/private"))
            assertEquals(WebReadResult.Suppressed(WebReadStatus.PRIVATE), read(activity, pages, "/login"))
            assertEquals(WebReadResult.Suppressed(WebReadStatus.UNSUPPORTED), read(activity, pages, "/frame"))
        }
    }

    @Test(timeout = 45_000)
    fun handoverReusesTheViewAndPausedOrExcludedSessionsCannotRead() = runBlocking {
        withBrowser { activity, pages ->
            assertTrue(read(activity, pages, "/public") is WebReadResult.Allowed)
            withContext(Dispatchers.Main) {
                activity.session.detach()
                activity.session.detach()
            }
            assertTrue(activity.session.observe(pages.origin) is WebReadResult.Suppressed)
            withContext(Dispatchers.Main) {
                val original = activity.findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup
                val first = original.getChildAt(0)
                val resumed = activity.session.attach(activity)
                assertTrue(first === resumed)
                activity.setContentView(resumed)
            }
            assertTrue(activity.session.observe(pages.origin) is WebReadResult.Allowed)
            withContext(Dispatchers.Main) { activity.session.excludeOrigin(pages.origin) }
            assertTrue(activity.session.observe(pages.origin) is WebReadResult.Suppressed)
        }
    }

    @Test(timeout = 45_000)
    fun privateDisplayRendersResizesAndHandsTheSameViewBackWithoutThePhoneHelper() = runBlocking {
        withBrowser { activity, pages ->
            assertTrue(read(activity, pages, "/public") is WebReadResult.Allowed)
            val view = withContext(Dispatchers.Main) {
                (activity.findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup)
                    .getChildAt(0) as android.webkit.WebView
            }
            val host = withContext(Dispatchers.Main) {
                PrivateWebHost.create(activity)
            }
            try {
                withContext(Dispatchers.Main) { host.park(activity.session) }
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) { view.width == 720 && view.height == 1280 }) delay(20)
                    while (host.renderedFrames == 0L) delay(20)
                }
                withContext(Dispatchers.Main) {
                    assertTrue(view.display.flags and android.view.Display.FLAG_PRIVATE != 0)
                    assertFalse(activity.session.state.value.soundAllowed)
                    host.resize(480, 800)
                }
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) { view.width == 480 && view.height == 800 }) delay(20)
                }
                assertTrue(activity.session.observe(pages.origin) is WebReadResult.Allowed)
                withContext(Dispatchers.Main) {
                    assertTrue(view === activity.session.attach(activity))
                    activity.setContentView(view)
                    host.close()
                }
                assertTrue(activity.session.observe(pages.origin) is WebReadResult.Allowed)
            } finally {
                withContext(Dispatchers.Main) { host.close() }
            }
        }
    }

    @Test(timeout = 45_000)
    fun rendererTerminationEndsTheSessionWithoutReloadingOrReplayingThePage() = runBlocking {
        withBrowser { activity, pages ->
            assertTrue(read(activity, pages, "/public") is WebReadResult.Allowed)
            withContext(Dispatchers.Main) {
                val content = activity.findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup
                val view = content.getChildAt(0) as android.webkit.WebView
                assertTrue(requireNotNull(view.webViewRenderProcess).terminate())
            }
            withTimeout(10_000) { activity.session.state.first { it.status == WebStatus.RENDERER_GONE } }
            assertTrue(activity.session.isClosed)
            assertTrue(activity.session.observe(pages.origin) is WebReadResult.Suppressed)
            delay(250)
            assertEquals(WebStatus.RENDERER_GONE, activity.session.state.value.status)
        }
    }

    @Test(timeout = 45_000)
    fun pageNavigationToAnotherOriginWaitsForExplicitUserConfirmation() = runBlocking {
        withBrowser { activity, pages ->
            assertTrue(read(activity, pages, "/public") is WebReadResult.Allowed)
            Pages().use { other ->
                withContext(Dispatchers.Main) {
                    val content = activity.findViewById<android.view.View>(
                        android.R.id.content
                    ) as android.view.ViewGroup
                    val view = content.getChildAt(0) as android.webkit.WebView
                    // Both origins are generated loopback fixtures, never interpolated website input.
                    view.evaluateJavascript("location.href='${other.origin}/public'", null)
                }
                withTimeout(5_000) { activity.session.state.first { it.pendingOrigin == other.origin } }
                assertTrue(activity.session.observe(pages.origin) is WebReadResult.Suppressed)
                withContext(Dispatchers.Main) { activity.session.allowPendingNavigation() }
                withTimeout(10_000) { activity.session.state.first { it.status == WebStatus.READY } }
                assertTrue(activity.session.observe(other.origin) is WebReadResult.Allowed)
                assertTrue(activity.session.observe(pages.origin) is WebReadResult.Suppressed)
            }
        }
    }

    @Test(timeout = 60_000)
    fun downloadApprovalIsCancelledOnPauseAndApprovedBytesEnterTheEncryptedWorkspace() = runBlocking {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true" &&
                android.os.Build.HARDWARE == "ranchu" && android.os.Build.FINGERPRINT.contains("sdk_gphone")
        )
        withBrowser { activity, pages ->
            val store = (activity.application as BuddyApplication).store
            store.clear()
            try {
                suspend fun request(): dev.buddy.browser.WebDownloadRequest {
                    assertTrue(read(activity, pages, "/public") is WebReadResult.Allowed)
                    withContext(Dispatchers.Main) {
                        val root = activity.findViewById<android.view.View>(
                            android.R.id.content
                        ) as android.view.ViewGroup
                        (root.getChildAt(0) as android.webkit.WebView)
                            .evaluateJavascript("location.href='${pages.origin}/download?token=synthetic'", null)
                    }
                    return requireNotNull(
                        withTimeout(10_000) {
                            activity.session.state.first { it.download != null }
                        }.download
                    )
                }
                val stale = request()
                assertEquals(1, pages.downloads.get())
                withContext(Dispatchers.Main) {
                    activity.session.detach()
                    assertEquals(null, activity.session.approveDownload(stale.id))
                    activity.session.resume()
                }
                val current = request()
                assertEquals(2, pages.downloads.get())
                val grant =
                    withContext(Dispatchers.Main) { requireNotNull(activity.session.approveDownload(current.id)) }
                BrowserDownloader().use { downloader ->
                    val file = downloader.fetch(grant)
                    try {
                        assertEquals(3, pages.downloads.get())
                        assertEquals(3, pages.authenticatedDownloads.get())
                        val workspace = java.util.UUID.randomUUID().toString()
                        store.put("conversation:$workspace", "conversation", "fixture")
                        val imported = store.artifacts.importFile(
                            workspace,
                            file.name,
                            file.mimeType,
                            android.net.Uri.parse(file.origin),
                            file.bytes
                        )
                        assertEquals(pages.origin, imported.sourceUri)
                        org.junit.Assert.assertArrayEquals(
                            file.bytes,
                            store.artifacts.read(workspace, imported.id, ArtifactReadBudget())
                        )
                    } finally {
                        file.bytes.fill(0)
                    }
                }
            } finally {
                store.clear()
            }
        }
    }
}
