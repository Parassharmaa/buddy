package dev.buddy

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.shell.ShellProtocol
import java.security.SecureRandom
import java.util.UUID
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
        val frames = FixtureFrameSink()
        var bootstrap: AuthenticatedHelperFixture? = null
        var stage = "bootstrap"
        var windowCount = 0
        var fixtureRootCount = 0
        try {
            val session = AuthenticatedHelperFixture.start(context, automation)
            bootstrap = session
            val connected = session.helper
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
            assertEquals(
                capabilities.getLong("authorizationDeadline"),
                connected.capabilities().getLong("authorizationDeadline")
            )
            assertThrows(IllegalStateException::class.java) { connected.renewLease(lease, challenge) }
            val token = connected.createDisplay(lease, 720, 1280, 240, frames.surface)
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
                        window.root?.let { findFixtureNode(it, predicate) }
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
            withTimeout(5_000) { while (frames.count == 0) delay(50) }
            stage = "verify_window_privacy_metadata"
            val privacy = connected.displayState(lease, token)
            check(privacy.getBoolean("windowMetadataKnown")) {
                "Window metadata unavailable (status=${privacy.getInt("windowMetadataStatus", -1)}; " +
                    "windows=${privacy.getInt(
                        "windowMetadataCount"
                    )}; scoped=${privacy.getInt("windowMetadataScopedCount")}; " +
                    "displays=${privacy.getIntArray("windowMetadataDisplays")?.contentToString()})"
            }
            check(!privacy.getBoolean("secureWindow") && !privacy.getBoolean("mixedWindowOwners")) {
                "Fixture privacy blocked; secure=${privacy.getBoolean("secureWindow")}; " +
                    "foreign=${privacy.getIntArray("windowMetadataForeign")?.contentToString()}; " +
                    "geometry=${privacy.getIntArray("windowMetadataForeignGeometry")?.contentToString()}; " +
                    "monitor=${privacy.getString("windowMetadataMonitorProbe")}"
            }
            stage = "takeover"
            connected.takeOverFixture(lease, token, UUID.randomUUID().toString())
            node(0) { it.text?.toString() == "Sent to Alice: $text" }
            assertThrows(IllegalStateException::class.java) {
                connected.tap(lease, token, UUID.randomUUID().toString(), bounds.centerX(), bounds.centerY())
            }
            stage = "handback"
            connected.returnFixture(lease, token, UUID.randomUUID().toString())
            node { it.text?.toString() == "Sent to Alice: $text" }
            stage = "foreign_activity_blocks_input"
            val launch = ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand(
                    "am start -W --display $display -f 0x18000000 " +
                        "-n dev.buddy/.MainActivity"
                )
            ).use { it.readNBytes(4096).toString(Charsets.UTF_8) }
            check(launch.contains("Status: ok")) { "Controlled foreign-activity launch was rejected" }
            withTimeout(5_000) {
                while (automation.windowsOnAllDisplays.get(display).orEmpty().none {
                        it.root?.packageName?.toString() == context.packageName
                    }
                ) {
                    delay(50)
                }
            }
            assertThrows(IllegalStateException::class.java) {
                connected.tap(lease, token, UUID.randomUUID().toString(), bounds.centerX(), bounds.centerY())
            }
            assertThrows(IllegalStateException::class.java) { connected.displayState(lease, token) }
            connected.releaseDisplay(lease, token)
            assertThrows(IllegalArgumentException::class.java) {
                connected.tap(lease, token, UUID.randomUUID().toString(), 10, 10)
            }
            connected.releaseLease(lease)
        } catch (failure: Exception) {
            throw AssertionError(
                "Fixture probe failed at $stage; frames=${frames.count}; windows=$windowCount; fixtureRoots=$fixtureRootCount",
                failure
            )
        } finally {
            bootstrap?.close()
            frames.close()
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }
}
