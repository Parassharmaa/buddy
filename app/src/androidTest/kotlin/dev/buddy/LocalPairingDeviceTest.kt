package dev.buddy

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
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
        val originalFlags = automation.serviceInfo.flags
        val configuration = Configurator.getInstance()
        val originalIdleTimeout = configuration.waitForIdleTimeout
        configuration.setWaitForIdleTimeout(500)
        val device = UiDevice.getInstance(instrumentation)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val context = instrumentation.targetContext
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use { it.readNBytes(4096).toString(Charsets.UTF_8).trim() }
        val original = shell("settings get global adb_wifi_enabled")
        val identity = AdbIdentity()
        var helper: IBuddyHelper? = null
        var code: CharArray? = null
        var stage = "enable_wireless_debugging"
        var sawConfirmation = false
        var allowedConfirmation = false
        var physicalWindowCount = 0
        var settingsVisible = false
        var wifiSupported = false
        var settingsLaunched = false
        var developerTitleSeen = false
        var scrollableSeen = false
        var scrollAttempts = 0
        var deepestNode = 0
        var treeLimited = false
        fun nodes(): List<AccessibilityNodeInfo> {
            automation.clearCache()
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                deepestNode = maxOf(deepestNode, depth)
                if (depth > 64 || result.size >= 2000) {
                    treeLimited = true
                    return
                }
                result += node
                for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, depth + 1) }
            }
            // Previous probes use virtual displays. The global active window may still refer
            // to one of them; Android's pairing UI must be read explicitly on the main display.
            val windows = automation.windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()
            physicalWindowCount = windows.size
            val roots = windows.mapNotNull { window -> window.root?.let { it to window.isFocused } }
            settingsVisible = roots.any { it.first.packageName?.toString() == "com.android.settings" }
            val focused = roots.firstOrNull {
                it.second && it.first.packageName?.toString() in setOf("com.android.settings", "com.android.systemui")
            }?.first
            val target = focused ?: roots.firstOrNull {
                it.first.packageName?.toString() == "com.android.settings"
            }?.first ?: roots.firstOrNull {
                it.first.packageName?.toString() == "com.android.systemui"
            }?.first
            target?.let { visit(it, 0) }
            return result
        }
        fun click(node: AccessibilityNodeInfo) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Settings control unavailable" }
        }
        fun allowNetwork() {
            val controls = nodes()
            if (controls.any {
                    it.text?.toString()?.startsWith("Allow wireless debugging", ignoreCase = true) == true
                }
            ) {
                sawConfirmation = true
                controls.firstOrNull {
                    it.viewIdResourceName == "android:id/button1" && it.isEnabled
                }?.let {
                    click(it)
                    allowedConfirmation = true
                }
            }
        }
        fun portIn(text: String): Int? = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:([0-9]{1,5})")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
        try {
            identity.forget()
            stage = "wireless_support"
            wifiSupported = shell("cmd adb is-wifi-supported") == "true"
            check(wifiSupported) { "Wireless debugging is unavailable on this emulator" }
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
            stage = "open_wireless_settings"
            // Newer adbd reports its TLS port over the system socket rather than the old
            // service.adb.tls.port property. Read the public Settings screen on every API.
            delay(300)
            allowNetwork()
            settingsLaunched = shell("am start -W -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                .contains("Status: ok", ignoreCase = true)
            var forward = true
            withTimeout(15_000) {
                while (true) {
                    allowNetwork()
                    val controls = nodes()
                    developerTitleSeen = developerTitleSeen || controls.any {
                        it.text?.toString()?.contains("Developer options", ignoreCase = true) == true
                    }
                    val item = device.findObject(
                        By.pkg(
                            "com.android.settings"
                        ).textContains("Wireless debugging").displayId(Display.DEFAULT_DISPLAY)
                    )
                    if (item != null) {
                        item.click()
                        break
                    }
                    device.findObject(
                        By.pkg("com.android.settings").scrollable(true).displayId(Display.DEFAULT_DISPLAY)
                    )?.let { scrollable ->
                        scrollableSeen = true
                        scrollAttempts++
                        // Wait for each gesture to settle and overlap pages so rows cannot be skipped.
                        if (!scrollable.scroll(if (forward) Direction.DOWN else Direction.UP, 0.75f)) {
                            forward = !forward
                        }
                    }
                    delay(100)
                }
            }
            stage = "wireless_connection_port"
            val connectPort = withTimeout(15_000) {
                while (true) {
                    allowNetwork()
                    nodes().firstNotNullOfOrNull { node -> node.text?.toString()?.let(::portIn) }
                        ?.let { return@withTimeout it }
                    delay(150)
                }
                error("Wireless connection details unavailable")
            }
            stage = "open_pairing_dialog"
            withTimeout(10_000) {
                while (true) {
                    val item = nodes().firstOrNull {
                        it.packageName?.toString() == "com.android.settings" && it.isVisibleToUser &&
                            it.text?.toString()?.contains("Pair device with pairing code", ignoreCase = true) == true
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
                    val port = text.firstNotNullOfOrNull(::portIn)
                    if (pairingCode != null && port != null) {
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
            val connectedHelper = requireNotNull(helper)
            assertEquals(ShellProtocol.SHELL_UID, connectedHelper.capabilities().getInt("helperUid"))
            stage = "fresh_transport_authorization_proof"
            val previousProof = connectedHelper.capabilities().getLong("authorizationDeadline")
            val proof = BootstrapProvider.prepare(ShellProtocol.REVALIDATE, connectedHelper)
            val challenge = proof.nonce.copyOf()
            try {
                connectedHelper.prepareAuthorization(challenge)
                withContext(Dispatchers.IO) {
                    // A distinct transport constructs a distinct TLS context: no pooled socket/session.
                    AdbTransport(identity).use { fresh ->
                        fresh.connect(LoopbackPort(connectPort))
                        fresh.bootstrap(BootstrapCommand.forInstalledApp(context, revalidate = true), challenge)
                    }
                }
                withTimeout(5_000) { proof.result.await() }
                check(connectedHelper.capabilities().getLong("authorizationDeadline") > previousProof)
            } finally {
                challenge.fill(0)
                BootstrapProvider.clear()
            }
        } catch (failure: Exception) {
            val enabled = runCatching { shell("settings get global adb_wifi_enabled") == "1" }.getOrNull()
            val tls = runCatching { shell("getprop persist.adb.tls_server.enable") == "1" }.getOrNull()
            val secure = runCatching { shell("getprop ro.adb.secure") == "1" }.getOrNull()
            val developer = runCatching {
                shell("settings get global development_settings_enabled") == "1"
            }.getOrNull()
            throw AssertionError(
                "Local pairing fixture failed at $stage; enabled=$enabled; tls=$tls; secure=$secure; " +
                    "confirmation=$sawConfirmation; allowed=$allowedConfirmation; " +
                    "developer=$developer; mainWindows=$physicalWindowCount; settingsVisible=$settingsVisible; " +
                    "supported=$wifiSupported; launched=$settingsLaunched; developerTitle=$developerTitleSeen; " +
                    "scrollable=$scrollableSeen; scrolls=$scrollAttempts; depth=$deepestNode; limited=$treeLimited",
                failure
            )
        } finally {
            code?.fill('\u0000')
            helper?.let { runCatching { it.disconnect() } }
            BootstrapProvider.clear()
            identity.forget()
            if (original != "1") shell("settings put global adb_wifi_enabled 0")
            configuration.setWaitForIdleTimeout(originalIdleTimeout)
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }
}
