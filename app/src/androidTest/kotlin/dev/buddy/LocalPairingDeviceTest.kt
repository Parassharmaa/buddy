package dev.buddy

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
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
import dev.buddy.connection.DaemonAuthorization
import dev.buddy.core.LoopbackPort
import dev.buddy.core.connection.ActiveLease
import dev.buddy.qualification.AdbPortProbe
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import java.io.IOException
import java.security.SecureRandom
import java.util.regex.Pattern
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// Explicitly opted-in disposable emulator only. Never run Settings automation on a user's phone.
// Pairing codes remain in memory and never enter screenshots, UI dumps, logs or assertions.
@RunWith(AndroidJUnit4::class)
class LocalPairingDeviceTest {
    private enum class Stage {
        ENABLE_WIRELESS_DEBUGGING,
        WIRELESS_SUPPORT,
        JOIN_EMULATOR_WIFI,
        RESET_WIRELESS_DEBUGGING,
        OPEN_WIRELESS_SETTINGS,
        ENABLE_WIRELESS_DETAIL_PAGE,
        WIRELESS_DAEMON_READY,
        OPEN_PAIRING_DIALOG,
        READ_PAIRING_DIALOG,
        LOCAL_TLS_PAIRING,
        FINISH_PAIRING_DIALOG,
        WIRELESS_CONNECTION_PORT,
        AUTHORIZED_TLS_CONNECTION,
        AUTHENTICATED_HELPER_HANDOFF,
        HELPER_AFTER_TRANSPORT_CLOSE,
        FRESH_TRANSPORT_AUTHORIZATION_PROOF,
        WIRELESS_DEBUGGING_REVOCATION
    }

    @Test(timeout = 90_000)
    fun localPairingAuthorizesTlsAndHelperSurvivesTransportClose() = runBlocking<Unit> {
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
        var stage = Stage.ENABLE_WIRELESS_DEBUGGING
        fun enter(next: Stage) {
            stage = next
            android.util.Log.i("BuddyQualification", "pairing_stage=${next.name}")
        }
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
        var wirelessPageSeen = false
        var pairingRowSeen = false
        var masterToggled = false
        var addressRowSeen = false
        var portOnlySeen = false
        var unavailableSeen = false
        var portPageVisible = false
        var portScrollAttempts = 0
        var portProbeStatus = "not_attempted"
        var pairingCodeSeen = false
        var pairingPortSeen = false
        var pairingDialogSeen = false
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

        @Suppress("DEPRECATION")
        fun checked(node: AccessibilityNodeInfo): Boolean = if (Build.VERSION.SDK_INT >= 36) {
            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            node.isChecked
        }
        fun click(node: AccessibilityNodeInfo): Boolean {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            return target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        fun allowNetwork(): Boolean {
            val controls = nodes()
            if (controls.any {
                    it.text?.toString()?.startsWith("Allow wireless debugging", ignoreCase = true) == true
                }
            ) {
                sawConfirmation = true
                controls.firstOrNull {
                    it.viewIdResourceName == "android:id/button1" && it.isEnabled
                }?.let {
                    if (click(it)) allowedConfirmation = true
                }
                return true
            }
            return false
        }
        fun portIn(text: String): Int? {
            val addressPort = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:([0-9]{1,5})").find(text)
            // Settings can format an empty IPv4 address as :port on an IPv6-only default link.
            val portOnly = Regex("^\\s*:\\s*([0-9]{1,5})\\s*$").find(text)
            return (addressPort ?: portOnly)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
        }
        try {
            enter(Stage.ENABLE_WIRELESS_DEBUGGING)
            identity.forget()
            enter(Stage.WIRELESS_SUPPORT)
            wifiSupported = shell("cmd adb is-wifi-supported") == "true"
            check(wifiSupported) { "Wireless debugging is unavailable on this emulator" }
            enter(Stage.JOIN_EMULATOR_WIFI)
            shell("svc wifi enable")
            if (!shell("cmd wifi status").contains("Wifi is connected to", ignoreCase = true)) {
                shell("cmd wifi connect-network AndroidWifi open")
            }
            withTimeout(10_000) {
                while (!shell("cmd wifi status").contains("Wifi is connected to", ignoreCase = true)) delay(200)
            }
            enter(Stage.RESET_WIRELESS_DEBUGGING)
            shell("settings put global adb_wifi_enabled 0")
            enter(Stage.OPEN_WIRELESS_SETTINGS)
            // Newer adbd reports its TLS port over the system socket rather than the old
            // service.adb.tls.port property. Read the public Settings screen on every API.
            delay(300)
            allowNetwork()
            settingsLaunched = shell("am start -W -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                .contains("Status: ok", ignoreCase = true)
            var forward = true
            withTimeout(15_000) {
                while (true) {
                    if (allowNetwork()) {
                        delay(150)
                        continue
                    }
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
            enter(Stage.ENABLE_WIRELESS_DETAIL_PAGE)
            withTimeout(15_000) {
                while (true) {
                    if (allowNetwork()) {
                        delay(150)
                        continue
                    }
                    val controls = nodes()
                    val toggle = controls.firstOrNull {
                        it.packageName?.toString() == "com.android.settings" && it.isCheckable && it.isEnabled
                    }
                    val title = controls.firstOrNull {
                        it.packageName?.toString() == "com.android.settings" &&
                            it.text?.toString()?.contains("Use wireless debugging", ignoreCase = true) == true
                    }
                    if (title != null) {
                        wirelessPageSeen = true
                        if (toggle != null && !checked(toggle) && !masterToggled) {
                            if (click(toggle)) masterToggled = true
                        }
                    } else if (!wirelessPageSeen) {
                        // A disabled primary-switch row can enable its switch before opening the fragment.
                        device.findObject(
                            By.pkg("com.android.settings").text("Wireless debugging").displayId(Display.DEFAULT_DISPLAY)
                        )?.click()
                    }
                    val pairingItem = controls.firstOrNull {
                        it.text?.toString()?.contains("Pair device with pairing code", ignoreCase = true) == true
                    }
                    pairingRowSeen = pairingItem != null
                    if (title != null && toggle?.let(::checked) == true && pairingItem?.isEnabled == true) break
                    delay(200)
                }
            }
            enter(Stage.WIRELESS_DAEMON_READY)
            withTimeout(15_000) {
                while (true) {
                    if (allowNetwork()) {
                        delay(150)
                        continue
                    }
                    if (nodes().any { it.text?.toString()?.let(::portIn) != null }) break
                    val probe = withContext(Dispatchers.IO) { probeDaemonPort(context, automation) }
                    portProbeStatus = probe.second
                    if (probe.first != null) break
                    delay(200)
                }
            }
            enter(Stage.OPEN_PAIRING_DIALOG)
            withTimeout(10_000) {
                while (true) {
                    val item = nodes().firstOrNull {
                        it.packageName?.toString() == "com.android.settings" && it.isVisibleToUser && it.isEnabled &&
                            it.text?.toString()?.contains("Pair device with pairing code", ignoreCase = true) == true
                    }
                    if (item != null && click(item)) break
                    delay(100)
                }
            }
            enter(Stage.READ_PAIRING_DIALOG)
            val pairingPort = withTimeout(10_000) {
                while (true) {
                    if (allowNetwork()) {
                        delay(150)
                        continue
                    }
                    val controls = nodes()
                    val text = controls.mapNotNull { it.text?.toString() }
                    val codeField = controls.firstOrNull {
                        it.viewIdResourceName == "com.android.settings:id/pairing_code" && it.isVisibleToUser
                    }
                    val addressField = controls.firstOrNull {
                        it.viewIdResourceName == "com.android.settings:id/ip_addr" && it.isVisibleToUser
                    }
                    val pairingCode = codeField?.text?.toString()?.takeIf {
                        it.matches(Regex("[0-9]{3}[\\s\\u00a0\\u202f]?[0-9]{3}"))
                    }
                    val port = addressField?.text?.toString()?.let(::portIn)
                    pairingCodeSeen = pairingCodeSeen || pairingCode != null
                    pairingPortSeen = pairingPortSeen || port != null
                    val dialogVisible = codeField != null || addressField != null
                    pairingDialogSeen = pairingDialogSeen || dialogVisible
                    if (!dialogVisible && text.any { it.contains("Use wireless debugging", ignoreCase = true) }) {
                        controls.firstOrNull {
                            it.text?.toString()?.contains("Pair device with pairing code", ignoreCase = true) == true &&
                                it.isEnabled && it.isVisibleToUser
                        }?.let(::click)
                    }
                    if (pairingCode != null && port != null) {
                        code = pairingCode.toCharArray()
                        return@withTimeout port
                    }
                    delay(100)
                }
                error("Pairing details unavailable")
            }
            val connectPort = withContext(Dispatchers.IO) {
                enter(Stage.LOCAL_TLS_PAIRING)
                PairingClient(identity).use { client ->
                    val secret = requireNotNull(code)
                    try {
                        client.pair(LoopbackPort(pairingPort), java.nio.CharBuffer.wrap(secret))
                    } finally {
                        secret.fill('\u0000')
                    }
                }
                enter(Stage.FINISH_PAIRING_DIALOG)
                withTimeout(10_000) {
                    while (true) {
                        val controls = nodes()
                        if (controls.any {
                                it.text?.toString()?.contains("Use wireless debugging", ignoreCase = true) == true
                            }
                        ) {
                            break
                        }
                        controls.firstOrNull {
                            it.packageName?.toString() == "com.android.settings" && it.isEnabled &&
                                it.text?.toString()?.lowercase() in setOf("close", "done")
                        }?.let(::click)
                        delay(100)
                    }
                }
                enter(Stage.WIRELESS_CONNECTION_PORT)
                val portSummary = Pattern.compile(
                    ".*(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:[0-9]{1,5}.*|\\s*:\\s*[0-9]{1,5}\\s*"
                )
                val connectPort = withTimeout(30_000) {
                    var searchUp = true
                    var attempt = 0
                    while (true) {
                        if (allowNetwork()) {
                            delay(150)
                            continue
                        }
                        val controls = nodes()
                        portPageVisible = controls.any {
                            it.text?.toString()?.let { text ->
                                text.contains("Use wireless debugging", ignoreCase = true) ||
                                    text.contains("Pair device with pairing code", ignoreCase = true)
                            } == true
                        }
                        addressRowSeen = addressRowSeen || controls.any {
                            it.text?.toString()?.contains("IP address", ignoreCase = true) == true
                        }
                        portOnlySeen = portOnlySeen || controls.any {
                            it.text?.toString()?.trim()?.matches(Regex(":\\s*[0-9]{1,5}")) == true
                        }
                        unavailableSeen = unavailableSeen || controls.any {
                            it.text?.toString()?.equals("Unavailable", ignoreCase = true) == true
                        }
                        controls.firstNotNullOfOrNull { node -> node.text?.toString()?.let(::portIn) }
                            ?.let { return@withTimeout it }
                        // A system dialog may be focused while the Settings details window remains visible.
                        val summaries = device.findObjects(
                            By.pkg("com.android.settings").displayId(Display.DEFAULT_DISPLAY)
                                .text(portSummary)
                        )
                        summaries.firstNotNullOfOrNull { it.text?.let(::portIn) }?.let { return@withTimeout it }
                        attempt++
                        if (attempt == 3 || (attempt % 5 == 0 && portProbeStatus == "BUDDY_ADB_PORT_NOT_READY")) {
                            val probe = probeDaemonPort(context, automation)
                            portProbeStatus = probe.second
                            probe.first?.let { return@withTimeout it }
                        }
                        if (portPageVisible && attempt % 5 == 0) {
                            device.findObject(
                                By.pkg("com.android.settings").scrollable(true).displayId(Display.DEFAULT_DISPLAY)
                            )?.let {
                                portScrollAttempts++
                                if (!it.scroll(if (searchUp) Direction.UP else Direction.DOWN, 0.7f)) {
                                    searchUp =
                                        !searchUp
                                }
                            }
                        }
                        delay(150)
                    }
                    error("Wireless connection details unavailable")
                }
                val pending = BootstrapProvider.prepare(ShellProtocol.HANDOFF)
                val nonce = pending.nonce.copyOf()
                try {
                    AdbTransport(identity).use { transport ->
                        enter(Stage.AUTHORIZED_TLS_CONNECTION)
                        transport.connect(LoopbackPort(connectPort))
                        enter(Stage.AUTHENTICATED_HELPER_HANDOFF)
                        transport.bootstrap(BootstrapCommand.forInstalledApp(context), nonce)
                        helper = requireNotNull(withTimeout(5_000) { pending.result.await() })
                    }
                } finally {
                    nonce.fill(0)
                }
                connectPort
            }
            enter(Stage.HELPER_AFTER_TRANSPORT_CLOSE)
            delay(1_000)
            val connectedHelper = requireNotNull(helper)
            assertEquals(ShellProtocol.SHELL_UID, connectedHelper.capabilities().getInt("helperUid"))
            enter(Stage.FRESH_TRANSPORT_AUTHORIZATION_PROOF)
            val previousProof = connectedHelper.capabilities().getLong("authorizationDeadline")
            val owner = Binder()
            val leaseChallenge = ByteArray(32).also(SecureRandom()::nextBytes)
            val acquired = try {
                connectedHelper.acquireLease(owner, leaseChallenge)
            } finally {
                leaseChallenge.fill(0)
            }
            val leaseId = requireNotNull(acquired.getString("lease"))
            var proofTransport: AdbTransport? = null
            var proofs = 0
            val stopped = CompletableDeferred<Exception>()
            val lease = ActiveLease(
                acquired.getLong("deadline"),
                SystemClock::elapsedRealtime,
                refreshAuthorization = {
                    DaemonAuthorization(context, identity).refresh(
                        connectedHelper,
                        LoopbackPort(connectPort),
                        onOpen = { proofTransport = it },
                        onClose = { if (proofTransport === it) proofTransport = null }
                    ).also { proofs++ }
                },
                renewLease = {
                    val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
                    val startedAt = SystemClock.elapsedRealtime()
                    try {
                        connectedHelper.renewLease(leaseId, challenge)
                    } finally {
                        challenge.fill(0)
                    }
                    startedAt + ShellProtocol.LEASE_MS
                },
                release = { connectedHelper.releaseLease(leaseId) }
            )
            val maintenance = launch {
                try {
                    lease.maintain()
                } catch (failure: Exception) {
                    stopped.complete(failure)
                }
            }
            try {
                withTimeout(20_000) {
                    while (proofs == 0 || lease.deadline <= acquired.getLong("deadline")) {
                        check(!stopped.isCompleted) { "Active authorization proof failed" }
                        delay(50)
                    }
                }
                val lastProof = connectedHelper.capabilities().getLong("authorizationDeadline")
                check(lastProof > previousProof && lease.isCurrent)
                enter(Stage.WIRELESS_DEBUGGING_REVOCATION)
                shell("settings put global adb_wifi_enabled 0")
                check(shell("settings get global adb_wifi_enabled") == "0")
                check(withTimeout(25_000) { stopped.await() } is IOException) {
                    "Revocation must reject the fresh daemon transport"
                }
                check(!lease.isCurrent)
                check(connectedHelper.capabilities().getLong("authorizationDeadline") == lastProof)
                val rejectedChallenge = ByteArray(32).also(SecureRandom()::nextBytes)
                try {
                    check(runCatching { connectedHelper.renewLease(leaseId, rejectedChallenge) }.isFailure)
                } finally {
                    rejectedChallenge.fill(0)
                }
            } finally {
                proofTransport?.close()
                maintenance.cancelAndJoin()
                runCatching { connectedHelper.releaseLease(leaseId) }
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
                    "scrollable=$scrollableSeen; scrolls=$scrollAttempts; depth=$deepestNode; limited=$treeLimited; " +
                    "wirelessPage=$wirelessPageSeen; pairingRow=$pairingRowSeen; masterToggled=$masterToggled; " +
                    "addressRow=$addressRowSeen; portOnly=$portOnlySeen; unavailable=$unavailableSeen; " +
                    "portPageVisible=$portPageVisible; portScrolls=$portScrollAttempts; portProbe=$portProbeStatus; " +
                    "pairCodeSeen=$pairingCodeSeen; pairPortSeen=$pairingPortSeen; pairDialogSeen=$pairingDialogSeen",
                failure
            )
        } finally {
            code?.fill('\u0000')
            helper?.let { runCatching { it.disconnect() } }
            BootstrapProvider.clear()
            identity.forget()
            shell(
                if (original ==
                    "1"
                ) {
                    "settings put global adb_wifi_enabled 1"
                } else {
                    "settings put global adb_wifi_enabled 0"
                }
            )
            configuration.setWaitForIdleTimeout(originalIdleTimeout)
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private fun probeDaemonPort(context: Context, automation: UiAutomation): Pair<Int?, String> {
        val arguments = BootstrapCommand.forInstalledApp(context).arguments.take(4) + AdbPortProbe::class.java.name
        // UiAutomation tokenizes arguments directly; shell quoting would become literal text.
        check(arguments.none { it.any(Char::isWhitespace) })
        val descriptors = automation.executeShellCommandRwe(arguments.joinToString(" "))
        return try {
            val poll = StructPollfd().apply {
                fd = descriptors[0].fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
            val deadline = SystemClock.elapsedRealtime() + 3_000
            val output = StringBuilder()
            val buffer = ByteArray(256)
            while (output.length < 1024) {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceIn(0, 3_000).toInt()
                if (remaining == 0 || Os.poll(arrayOf(poll), remaining) <= 0) break
                val count = Os.read(descriptors[0].fileDescriptor, buffer, 0, buffer.size)
                if (count <= 0) break
                output.append(String(buffer, 0, count, Charsets.UTF_8))
                val port = Regex("(?m)^BUDDY_ADB_PORT:([0-9]{1,5})$").find(output)?.groupValues?.get(1)
                    ?.toIntOrNull()?.takeIf { it in 1..65535 }
                if (port != null) return port to "ready"
                val status = output.lineSequence().firstOrNull {
                    it.matches(Regex("BUDDY_ADB_PORT_(?:NOT_READY|FAILED:[A-Za-z0-9_]{1,64})"))
                }
                if (status != null) return null to status
            }
            null to if (output.isEmpty()) "empty" else "unrecognized"
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }
}
