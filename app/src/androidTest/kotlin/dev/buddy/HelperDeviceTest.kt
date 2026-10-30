package dev.buddy

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.adb.BootstrapCommand
import dev.buddy.connection.BootstrapProvider
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        )
        val digest = MessageDigest.getInstance(
            "SHA-256"
        ).digest(requireNotNull(info.signingInfo).apkContentsSigners.single().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val command = BootstrapCommand.helper(
            context.applicationInfo.sourceDir,
            context.packageName,
            context.applicationInfo.uid,
            digest
        )
        val pending = BootstrapProvider.prepare(ShellProtocol.HANDOFF)
        val process = automation.executeShellCommandRw(command.value)
        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2)
        var helper: IBuddyHelper? = null
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(process[1]).use {
                it.write((Base64.encodeToString(pending.nonce, Base64.NO_WRAP) + "\n").toByteArray())
            }
            val connected = requireNotNull(withTimeout(15_000) { pending.result.await() })
            helper = connected
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
            val lease =
                requireNotNull(
                    connected.acquireLease(Binder(), ByteArray(32).also(SecureRandom()::nextBytes)).getString("lease")
                )
            val token = connected.createDisplay(lease, 720, 1280, 240, reader.surface)
            val display = connected.launchFixture(lease, token, UUID.randomUUID().toString())
            assertTrue(display > 0)
            assertNotEquals(token, display.toString())
            suspend fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
                withTimeout(5_000) {
                    while (true) {
                        val windows = automation.windowsOnAllDisplays.get(display).orEmpty()
                        val found = windows.firstNotNullOfOrNull { window ->
                            window.root?.let { find(it, predicate) }
                        }
                        if (found != null) return@withTimeout found
                        delay(100)
                    }
                    error("No fixture window")
                }
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
            node { it.text?.toString() == text }
            val send = node { it.text?.toString() == "Send to fixture inbox" }
            val bounds = Rect().also(send::getBoundsInScreen)
            connected.tap(lease, token, UUID.randomUUID().toString(), bounds.centerX(), bounds.centerY())
            node { it.text?.toString() == "Sent to Alice: $text" }
            connected.releaseDisplay(lease, token)
            connected.releaseLease(lease)
        } finally {
            helper?.let { runCatching { it.disconnect() } }
            reader.close()
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
