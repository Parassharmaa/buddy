package dev.buddy

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
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
        var stage = "enable_wireless_debugging"
        fun nodes(): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 20 || result.size >= 500) return
                result += node
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
            }
            automation.rootInActiveWindow?.let { visit(it, 0) }
            return result
        }
        fun click(node: AccessibilityNodeInfo) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Settings control unavailable" }
        }
        try {
            identity.forget()
            stage = "join_emulator_wifi"
            shell("svc wifi enable")
            if (!shell("cmd wifi status").contains("Wifi is connected to", ignoreCase = true)) {
                shell("cmd wifi connect-network AndroidWifi open")
            }
            withTimeout(10_000) {
                while (!shell("cmd wifi status").contains("Wifi is connected to", ignoreCase = true)) delay(200)
            }
            stage = "enable_wireless_debugging"
            shell("settings put global adb_wifi_enabled 1")
            stage = "wireless_connection_port"
            val connectPort = withTimeout(15_000) {
                var port = 0
                while (port !in 1..65535) {
                    delay(200)
                    val controls = nodes()
                    if (controls.any {
                            it.text?.toString()?.startsWith("Allow wireless debugging", ignoreCase = true) == true
                        }
                    ) {
                        controls.firstOrNull { it.text?.toString()?.equals("Allow", ignoreCase = true) == true }
                            ?.let(::click)
                    }
                    port = shell("getprop service.adb.tls.port").toIntOrNull() ?: 0
                }
                port
            }
            stage = "open_wireless_settings"
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            withTimeout(10_000) {
                while (true) {
                    val controls = nodes()
                    val item = controls.firstOrNull {
                        it.text?.toString()?.equals("Wireless debugging", ignoreCase = true) == true
                    }
                    if (item != null) {
                        click(item)
                        break
                    }
                    controls.firstOrNull { it.isScrollable }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(150)
                }
            }
            stage = "open_pairing_dialog"
            withTimeout(10_000) {
                while (true) {
                    val item = nodes().firstOrNull {
                        it.text?.toString()?.equals("Pair device with pairing code", ignoreCase = true) == true
                    }
                    if (item != null) {
                        click(item)
                        break
                    }
                    delay(100)
                }
            }
            stage = "read_pairing_dialog"
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
                stage = "local_tls_pairing"
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
                        stage = "authorized_tls_connection"
                        transport.connect(LoopbackPort(connectPort))
                        stage = "authenticated_helper_handoff"
                        transport.bootstrap(BootstrapCommand.forInstalledApp(context), nonce)
                        helper = requireNotNull(withTimeout(5_000) { pending.result.await() })
                    }
                } finally {
                    nonce.fill(0)
                }
            }
            stage = "helper_after_transport_close"
            delay(1_000)
            assertEquals(ShellProtocol.SHELL_UID, requireNotNull(helper).capabilities().getInt("helperUid"))
        } catch (failure: Exception) {
            throw AssertionError("Local pairing fixture failed at $stage", failure)
        } finally {
            code?.fill('\u0000')
            helper?.let { runCatching { it.disconnect() } }
            BootstrapProvider.clear()
            identity.forget()
            if (original != "1") shell("settings put global adb_wifi_enabled 0")
        }
    }
}
