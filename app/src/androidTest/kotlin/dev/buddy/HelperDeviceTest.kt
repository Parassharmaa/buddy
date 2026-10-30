package dev.buddy

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.adb.BootstrapCommand
import dev.buddy.connection.BootstrapProvider
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Controlled fixture only. This test uses the instrumentation shell, not a model or a product shell tool.
@RunWith(AndroidJUnit4::class)
class HelperDeviceTest {
    @Test fun authenticatedHelperOwnsItsDisplayAndRoutesFixtureInput() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val command = BootstrapCommand.forInstalledApp(context)
        val pending = BootstrapProvider.prepare(ShellProtocol.HANDOFF)
        // UiAutomation tokenizes argv without shell parsing. Installed APK paths contain no whitespace.
        check(command.arguments.none { argument -> argument.any(Char::isWhitespace) })
        val process = automation.executeShellCommandRwe(command.arguments.joinToString(" "))
        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        val frames = HandlerThread("fixture-frame-drain").apply { start() }
        val frameLock = Any()
        var captureOpen = true
        val frameCount = AtomicInteger()
        reader.setOnImageAvailableListener({ source ->
            synchronized(frameLock) {
                if (captureOpen) source.acquireLatestImage()?.use { frameCount.incrementAndGet() }
            }
        }, Handler(frames.looper))
        var helper: IBuddyHelper? = null
        var stage = "bootstrap"
        var windowCount = 0
        var fixtureRootCount = 0
        try {
            val connected = try {
                ParcelFileDescriptor.AutoCloseOutputStream(process[1]).use {
                    it.write((Base64.encodeToString(pending.nonce, Base64.NO_WRAP) + "\n").toByteArray())
                }
                requireNotNull(withTimeout(15_000) { pending.result.await() })
            } catch (failure: Exception) {
                val errors = ParcelFileDescriptor.AutoCloseInputStream(process[2])
                val text = errors.readNBytes(minOf(errors.available(), 1024)).toString(Charsets.UTF_8)
                val reason = text.lineSequence().firstOrNull { it.startsWith("BUDDY_BOOTSTRAP_FAILED:") }
                throw AssertionError(reason ?: "Helper handoff did not complete", failure)
            }
            helper = connected
            stage = "capabilities_and_lease"
            val capabilities = connected.capabilities()
            assertEquals(ShellProtocol.SHELL_UID, capabilities.getInt("helperUid"))
            assertEquals(ShellProtocol.VERSION, capabilities.getInt("protocol"))
            assertFalse(capabilities.getBoolean("qualified"))
            assertThrows(IllegalStateException::class.java) {
                context.contentResolver.call(
                    context.packageName + ShellProtocol.AUTHORITY_SUFFIX,
                    ShellProtocol.HANDOFF,
                    null,
                    Bundle().apply {
                        putByteArray("nonce", ByteArray(32))
                        putInt("protocol", ShellProtocol.VERSION)
                    }
                )
            }
            val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
            val lease = requireNotNull(connected.acquireLease(Binder(), challenge).getString("lease"))
            assertThrows(IllegalStateException::class.java) { connected.renewLease(lease, challenge) }
            connected.renewLease(lease, ByteArray(32).also(SecureRandom()::nextBytes))
            assertThrows(IllegalStateException::class.java) { connected.renewLease(lease, challenge) }
            val token = connected.createDisplay(lease, 720, 1280, 240, reader.surface)
            stage = "fixture_launch"
            val display = connected.launchFixture(lease, token, UUID.randomUUID().toString())
            assertTrue(display > 0)
            assertThrows(IllegalArgumentException::class.java) {
                connected.tap(lease, display.toString(), UUID.randomUUID().toString(), 10, 10)
            }
            suspend fun node(
                onDisplay: Int = display,
                predicate: (AccessibilityNodeInfo) -> Boolean
            ): AccessibilityNodeInfo = withTimeout(5_000) {
                while (true) {
                    val windows = automation.windowsOnAllDisplays.get(onDisplay).orEmpty()
                    windowCount = windows.size
                    fixtureRootCount = windows.count { it.root?.packageName?.toString() == "dev.buddy.fixture" }
                    val found = windows.firstNotNullOfOrNull { window ->
                        window.root?.let { find(it, predicate) }
                    }
                    if (found != null) return@withTimeout found
                    delay(100)
                }
                error("No fixture window")
            }
            stage = "observe_editor"
            val editor = node { it.isEditable }
            val text = "こんにちは Buddy · café"
            assertTrue(
                editor.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                )
            )
            stage = "verify_unicode_editor"
            node { it.text?.toString() == text }
            stage = "observe_send_button"
            val send = node { it.text?.toString()?.equals("Send to fixture inbox", ignoreCase = true) == true }
            val bounds = Rect().also(send::getBoundsInScreen)
            connected.tap(lease, token, UUID.randomUUID().toString(), bounds.centerX(), bounds.centerY())
            stage = "verify_fixture_effect"
            node { it.text?.toString() == "Sent to Alice: $text" }
            stage = "verify_frames"
            withTimeout(5_000) { while (frameCount.get() == 0) delay(50) }
            stage = "takeover"
            connected.takeOverFixture(lease, token, UUID.randomUUID().toString())
            node(0) { it.text?.toString() == "Sent to Alice: $text" }
            assertThrows(IllegalStateException::class.java) {
                connected.tap(lease, token, UUID.randomUUID().toString(), bounds.centerX(), bounds.centerY())
            }
            stage = "handback"
            connected.returnFixture(lease, token, UUID.randomUUID().toString())
            node { it.text?.toString() == "Sent to Alice: $text" }
            connected.releaseDisplay(lease, token)
            assertThrows(IllegalArgumentException::class.java) {
                connected.tap(lease, token, UUID.randomUUID().toString(), 10, 10)
            }
            connected.releaseLease(lease)
        } catch (failure: Exception) {
            throw AssertionError(
                "Fixture probe failed at $stage; frames=${frameCount.get()}; windows=$windowCount; fixtureRoots=$fixtureRootCount",
                failure
            )
        } finally {
            helper?.let { runCatching { it.disconnect() } }
            synchronized(frameLock) {
                captureOpen = false
                reader.setOnImageAvailableListener(null, null)
                reader.close()
            }
            frames.quitSafely()
            process.forEach { runCatching { it.close() } }
            BootstrapProvider.clear()
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun find(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > 20 || node.packageName?.toString() != "dev.buddy.fixture") return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> find(child, predicate, depth + 1)?.let { return it } }
        }
        return null
    }
}
