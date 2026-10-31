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
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
                                "/upload" ->
                                    "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                                        "<button id='pick' style='width:240px;height:80px' " +
                                        "onclick=\"window.fixtureClicks=(window.fixtureClicks||0)+1;" +
                                        "document.getElementById('upload').click()\">" +
                                        "Choose synthetic file</button>" +
                                        "<input id='upload' type='file' accept='.txt'>" +
                                        "<script>window.uploadResults=[];" +
                                        "document.getElementById('upload').addEventListener('change',function(){" +
                                        "if(!this.files[0])return;const r=new FileReader();" +
                                        "r.onload=()=>window.uploadResults.push(r.result);" +
                                        "r.onerror=()=>window.uploadResults.push('READ_FAILED');" +
                                        "r.readAsText(this.files[0]);});</script>"

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
        var primaryFailure: Throwable? = null
        try {
            Pages().use { pages ->
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) { activity.session.hasWindow }) delay(20)
                }
                block(activity, pages)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val cleanup = runCatching {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    try {
                        activity.session.clearProfile()
                    } finally {
                        activity.finish()
                    }
                }
            }.exceptionOrNull()
            val idle = runCatching { instrumentation.waitForIdleSync() }.exceptionOrNull()
            val original = primaryFailure
            if (original != null) {
                listOfNotNull(cleanup, idle).filter { it !== original }.forEach(original::addSuppressed)
            } else if (cleanup != null) {
                if (idle != null && idle !== cleanup) cleanup.addSuppressed(idle)
                throw cleanup
            } else if (idle != null) {
                throw idle
            }
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

    private suspend fun script(view: android.webkit.WebView, expression: String): String =
        withContext(Dispatchers.Main) {
            withTimeout(2_000) {
                suspendCancellableCoroutine { continuation ->
                    view.evaluateJavascript(expression) { value ->
                        if (continuation.isActive) continuation.resume(value)
                    }
                }
            }
        }

    @Test(timeout = 60_000)
    fun browserUploadAcceptsOnlyAnExplicitPrivateWorkspaceFileAndKeepsThePagePrivate() = runBlocking {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true" &&
                android.os.Build.HARDWARE == "ranchu" && android.os.Build.FINGERPRINT.contains("sdk_gphone")
        )
        withBrowser { activity, pages ->
            val store = (activity.application as BuddyApplication).store
            val resolver = activity.contentResolver
            var outside: android.net.Uri? = null
            store.clear()
            try {
                val workspace = java.util.UUID.randomUUID().toString()
                store.put(
                    "conversation:$workspace",
                    "conversation",
                    Json.encodeToString(dev.buddy.core.Conversation(workspace, "Synthetic upload"))
                )
                val approved = "Synthetic approved upload · café · こんにちは"
                val artifact = store.artifacts.importFile(
                    workspace,
                    "approved.txt",
                    "text/plain",
                    android.net.Uri.parse("content://fixture/upload"),
                    approved.toByteArray()
                )
                val unapproved = requireNotNull(
                    resolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "buddy-unapproved-upload.txt")
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    )
                ).also { outside = it }
                dev.buddy.files.DocumentAccess(activity).write(unapproved, "Outside workspace canary".toByteArray())
                resolver.update(
                    unapproved,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                withContext(Dispatchers.Main) { activity.session.navigate(WebAddress.parse(pages.origin + "/upload")) }
                withTimeout(10_000) { activity.session.state.first { it.status == WebStatus.READY } }
                val view = withContext(Dispatchers.Main) {
                    val root = activity.findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup
                    root.getChildAt(0) as android.webkit.WebView
                }
                var stage = "FIRST_CHOOSER"
                var expectedClicks = 0
                suspend fun choose() {
                    withContext(Dispatchers.Main) {
                        withTimeout(2_000) {
                            suspendCancellableCoroutine { continuation ->
                                view.postVisualStateCallback(
                                    0,
                                    object : android.webkit.WebView.VisualStateCallback() {
                                        override fun onComplete(requestId: Long) {
                                            if (continuation.isActive) continuation.resume(Unit)
                                        }
                                    }
                                )
                            }
                        }
                        assertTrue("Upload fixture is not visible", view.isShown)
                        assertTrue("Upload fixture has no input focus", view.hasWindowFocus())
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    val device = androidx.test.uiautomator.UiDevice.getInstance(
                        InstrumentationRegistry.getInstrumentation()
                    )
                    val button = requireNotNull(
                        device.wait(
                            androidx.test.uiautomator.Until.findObject(
                                androidx.test.uiautomator.By.text("Choose synthetic file")
                            ),
                            2_000
                        )
                    ) { "Upload fixture button is absent from the current window" }
                    assertEquals(activity.packageName, button.applicationPackage)
                    val bounds = button.visibleBounds
                    withContext(Dispatchers.Main) {
                        val position = IntArray(2).also(view::getLocationOnScreen)
                        assertTrue(bounds.width() > 0 && bounds.height() > 0)
                        assertTrue(
                            android.graphics.Rect(
                                position[0],
                                position[1],
                                position[0] + view.width,
                                position[1] + view.height
                            ).contains(bounds.centerX(), bounds.centerY())
                        )
                    }
                    button.click()
                    expectedClicks++
                    try {
                        withTimeout(2_000) {
                            while (script(view, "window.fixtureClicks||0") != expectedClicks.toString()) delay(20)
                        }
                    } catch (failure: Exception) {
                        throw AssertionError("Upload fixture did not receive the gesture at $stage", failure)
                    }
                    try {
                        withTimeout(5_000) { activity.session.state.first { it.hasUploadRequest } }
                    } catch (failure: Exception) {
                        throw AssertionError("Upload chooser did not open at $stage", failure)
                    }
                }
                choose()
                withContext(Dispatchers.Main) { activity.session.chooseFiles(listOf(unapproved)) }
                withTimeout(2_000) { activity.session.state.first { !it.hasUploadRequest } }
                stage = "SECOND_CHOOSER"
                choose()
                val uri = android.net.Uri.Builder().scheme("content").authority(activity.packageName + ".artifacts")
                    .appendPath(workspace).appendPath(artifact.id).build()
                withContext(Dispatchers.Main) { activity.session.chooseFiles(listOf(uri)) }
                try {
                    withTimeout(5_000) {
                        while (true) {
                            val values = Json.parseToJsonElement(
                                Json.parseToJsonElement(
                                    script(view, "JSON.stringify(window.uploadResults)")
                                ).jsonPrimitive.content
                            ).jsonArray
                            if (values.isNotEmpty()) {
                                assertEquals(listOf(approved), values.map { it.jsonPrimitive.content })
                                break
                            }
                            delay(20)
                        }
                    }
                } catch (failure: Exception) {
                    throw AssertionError("Approved upload did not produce readable fixture bytes", failure)
                }
                assertEquals(WebReadResult.Suppressed(WebReadStatus.PRIVATE), activity.session.observe(pages.origin))
            } finally {
                outside?.let { resolver.delete(it, null, null) }
                store.clear()
            }
        }
    }

    @Test(timeout = 60_000)
    fun clearingTheProfileClosesThePageAndRemovesStorageBeforeAFreshSession() = runBlocking {
        withBrowser { activity, pages ->
            assertTrue(read(activity, pages, "/public") is WebReadResult.Allowed)
            suspend fun evaluate(expression: String): String = withContext(Dispatchers.Main) {
                val root = activity.findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup
                val view = root.getChildAt(0) as android.webkit.WebView
                val result = kotlinx.coroutines.CompletableDeferred<String>()
                view.evaluateJavascript(expression) { result.complete(it) }
                withTimeout(2_000) { result.await() }
            }
            assertEquals(
                "true",
                evaluate(
                    "localStorage.setItem('buddy-fixture','synthetic');" +
                        "localStorage.getItem('buddy-fixture') === 'synthetic'"
                )
            )
            withContext(Dispatchers.Main) {
                assertTrue(android.webkit.CookieManager.getInstance().hasCookies())
                activity.session.clearProfile()
                assertTrue(activity.session.isClosed)
                assertFalse(android.webkit.CookieManager.getInstance().hasCookies())
                activity.resetSession()
            }
            withTimeout(5_000) {
                while (!withContext(Dispatchers.Main) { activity.session.hasWindow }) delay(20)
            }
            assertTrue(read(activity, pages, "/public") is WebReadResult.Allowed)
            assertEquals("null", evaluate("localStorage.getItem('buddy-fixture')"))
        }
    }
}
