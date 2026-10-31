package dev.buddy

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FocusIsolationDeviceTest {
    @Test fun virtualFixtureInputPreservesPhysicalKeyboardRouting() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use {
            it.readNBytes(4096).toString(Charsets.UTF_8)
        }
        val ui = FixtureUi(automation)
        var stage = "bootstrap"
        var virtualWindows = 0
        var virtualRoots = 0
        var virtualEditorSeen = false
        var draftAccepted = false
        var virtualActionDisplay = -1
        var virtualDraftLength = -1
        var physicalDraftPreserved = false
        var physicalReceivedVirtualDraft = false
        var virtualIsMasked = false
        var virtualHasReplacement = false
        var virtualIgnoresCaseMatch = false
        var virtualMismatchPositions: List<Int> = emptyList()
        try {
            AuthenticatedHelperFixture.start(instrumentation.targetContext, automation).use { fixture ->
                FixtureFrameSink().use { frames ->
                    val helper = fixture.helper
                    val owner = Binder()
                    val lease = requireNotNull(
                        helper.acquireLease(owner, ByteArray(32).also(SecureRandom()::nextBytes)).getString("lease")
                    )
                    try {
                        val token = helper.createDisplay(lease, 720, 1280, 240, frames.surface)
                        val display = helper.launchFixture(lease, token, UUID.randomUUID().toString())
                        stage = "physical_fixture"
                        val launch = shell(
                            "am start -W --display 0 -f 0x18000000 " +
                                "-n dev.buddy.fixture/.FixtureActivity"
                        )
                        check(launch.contains("Status: ok")) { "Physical fixture launch was rejected" }
                        val physical = ui.node(0) { it.isEditable }
                        check(physical.isFocused || physical.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                        check(physical.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        stage = "physical_typing_before_virtual_input"
                        typePhysical(automation, "physicalone")
                        ui.node(0) { it.isEditable && it.text?.toString() == "physicalone" }
                        stage = "virtual_ownership_after_physical_launch"
                        helper.displayState(lease, token)
                        stage = "virtual_editor_after_physical_launch"
                        val windows = automation.windowsOnAllDisplays.get(display).orEmpty()
                        virtualWindows = windows.size
                        virtualRoots = windows.count { it.root?.packageName?.toString() == "dev.buddy.fixture" }
                        val editor = ui.node(display) { it.isEditable }
                        virtualEditorSeen = true
                        virtualActionDisplay = editor.window?.displayId ?: -1
                        stage = "verify_virtual_node_scope"
                        check(virtualActionDisplay == display)
                        stage = "virtual_unicode_draft"
                        val message = "こんにちは · café"
                        draftAccepted = editor.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    message
                                )
                            }
                        )
                        assertTrue(draftAccepted)
                        stage = "verify_virtual_unicode_draft"
                        val physicalAfter = ui.node(0) { it.isEditable }.text?.toString()
                        physicalDraftPreserved = physicalAfter == "physicalone"
                        physicalReceivedVirtualDraft = physicalAfter == message
                        val actual = ui.node(display) { it.isEditable }.text?.toString()
                        virtualDraftLength = actual?.length ?: -1
                        virtualIsMasked = !actual.isNullOrEmpty() && actual.all { it in "•●*" }
                        virtualHasReplacement = actual?.any { it == '\ufffd' || it == '?' } == true
                        virtualIgnoresCaseMatch = actual?.equals(message, ignoreCase = true) == true
                        virtualMismatchPositions = message.indices.filter { actual?.getOrNull(it) != message[it] }
                        ui.node(display) { it.isEditable && it.text?.toString() == message }
                        stage = "observe_virtual_send_button"
                        val send = ui.node(display) {
                            it.text?.toString()?.equals("Send to fixture inbox", ignoreCase = true) == true
                        }
                        val bounds = Rect().also(send::getBoundsInScreen)
                        stage = "concurrent_keyboard_and_virtual_send"
                        coroutineScope {
                            val typingStarted = CompletableDeferred<Unit>()
                            val typing = async(Dispatchers.IO) {
                                typePhysical(automation, "twothreefour", typingStarted)
                            }
                            typingStarted.await()
                            check(!typing.isCompleted)
                            helper.tap(lease, token, UUID.randomUUID().toString(), bounds.centerX(), bounds.centerY())
                            typing.await()
                        }
                        ui.node(display) { it.text?.toString() == "Sent to Alice: $message" }
                        stage = "verify_physical_keyboard_route"
                        ui.node(0) { it.isEditable && it.text?.toString() == "physicalonetwothreefour" }
                        ui.node(display) { it.text?.toString() == "Sent to Alice: $message" }
                        assertTrue(owner.isBinderAlive)
                    } finally {
                        runCatching { helper.releaseLease(lease) }
                    }
                }
            }
        } catch (failure: Exception) {
            throw AssertionError(
                "Focus isolation fixture failed at $stage; virtualWindows=$virtualWindows; " +
                    "virtualRoots=$virtualRoots; editorSeen=$virtualEditorSeen; draftAccepted=$draftAccepted; " +
                    "actionDisplay=$virtualActionDisplay; virtualDraftLength=$virtualDraftLength; " +
                    "physicalPreserved=$physicalDraftPreserved; " +
                    "physicalReceivedVirtual=$physicalReceivedVirtualDraft; " +
                    "masked=$virtualIsMasked; replacement=$virtualHasReplacement; " +
                    "caseMatch=$virtualIgnoresCaseMatch; " +
                    "mismatchPositions=$virtualMismatchPositions",
                failure
            )
        } finally {
            // Both activities belong to this disposable, network-free test fixture.
            shell("am force-stop dev.buddy.fixture")
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private suspend fun typePhysical(
        automation: UiAutomation,
        value: String,
        started: CompletableDeferred<Unit>? = null
    ) {
        val events =
            requireNotNull(KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray()))
        val held = mutableMapOf<Int, KeyEvent>()
        try {
            for (event in events) {
                if (event.action == KeyEvent.ACTION_DOWN) held[event.keyCode] = event
                // KeyCharacterMap uses INVALID_DISPLAY: preserve the global keyboard route.
                check(automation.injectInputEvent(event, true))
                started?.complete(Unit)
                if (event.action == KeyEvent.ACTION_UP) held.remove(event.keyCode)
                if (started != null) delay(20)
            }
        } finally {
            held.values.forEach { event ->
                runCatching { automation.injectInputEvent(KeyEvent.changeAction(event, KeyEvent.ACTION_UP), true) }
            }
        }
    }
}
