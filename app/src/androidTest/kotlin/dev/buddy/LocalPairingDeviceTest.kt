package dev.buddy

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.adb.AdbIdentity
import dev.buddy.adb.AdbTransport
import dev.buddy.adb.BootstrapCommand
import dev.buddy.adb.PairingClient
import dev.buddy.connection.BootstrapProvider
import dev.buddy.core.LoopbackPort
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// Explicitly opted-in disposable emulator only. Never run Settings automation on a user's phone.
// Pairing codes remain in memory: no screenshots, UI dumps, logcat, or assertion values.
@RunWith(AndroidJUnit4::class)
class LocalPairingDeviceTest {
    @Test fun localPairingAuthorizesTlsAndHelperSurvivesTransportClose() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use { it.readNBytes(4096).toString(Charsets.UTF_8).trim() }
        val original = shell("settings get global adb_wifi_enabled")
        val identity = AdbIdentity()
        var helper: IBuddyHelper? = null
        var code: CharArray? = null
        try {
            identity.forget()
            shell("settings put global adb_wifi_enabled 1")
            val connectPort = withTimeout(15_000) {
                var port = 0
                while (port !in 1..65535) {
                    delay(200)
                    port = shell("getprop service.adb.tls.port").toIntOrNull() ?: 0
                }
                port
            }
            context.startActivity(
                Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            suspend fun nodes(): List<AccessibilityNodeInfo> {
                val result = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo, depth: Int) {
                    if (depth > 20 || result.size >= 500) return
                    result += node
                    for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
                }
                automation.rootInActiveWindow?.let { visit(it, 0) }
                return result
            }
            withTimeout(10_000) {
                while (true) {
                    val item = nodes().firstOrNull {
                        it.text?.toString()?.equals("Pair device with pairing code", ignoreCase = true) == true
                    }
                    if (item != null) {
                        var clickable: AccessibilityNodeInfo? = item
                        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                        check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                            "Pairing dialog could not be opened"
                        }
                        break
                    }
                    delay(100)
                }
            }
            val pairingPort = withTimeout(10_000) {
                while (true) {
                    val text = nodes().mapNotNull { it.text?.toString() }
                    val pairingCode = text.firstOrNull { it.matches(Regex("[0-9]{6}|[0-9]{3} [0-9]{3}")) }
                    val address = text.firstNotNullOfOrNull {
                        Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:([0-9]{1,5})").find(it)
                    }
                    val port = address?.groupValues?.get(1)?.toIntOrNull()
                    if (pairingCode != null && port != null && port in 1..65535) {
                        code = pairingCode.toCharArray()
                        return@withTimeout port
                    }
                    delay(100)
                }
                error("Pairing details unavailable")
            }
            withContext(Dispatchers.IO) {
                PairingClient(identity).use { client ->
                    val secret = requireNotNull(code)
                    try {
                        client.pair(LoopbackPort(pairingPort), java.nio.CharBuffer.wrap(secret))
                    } finally {
                        secret.fill('\u0000')
                    }
                }
                val pending = BootstrapProvider.prepare(ShellProtocol.HANDOFF)
                val nonce = pending.nonce.copyOf()
                try {
                    AdbTransport(identity).use { transport ->
                        transport.connect(LoopbackPort(connectPort))
                        transport.bootstrap(BootstrapCommand.forInstalledApp(context), nonce)
                        helper = requireNotNull(withTimeout(5_000) { pending.result.await() })
                    }
                } finally {
                    nonce.fill(0)
                }
            }
            delay(1_000)
            assertEquals(ShellProtocol.SHELL_UID, requireNotNull(helper).capabilities().getInt("helperUid"))
        } finally {
            code?.fill('\u0000')
            helper?.let { runCatching { it.disconnect() } }
            BootstrapProvider.clear()
            identity.forget()
            if (original != "1") shell("settings put global adb_wifi_enabled 0")
        }
    }
}
