package dev.buddy

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.ChatMessage
import dev.buddy.core.FunctionCall
import dev.buddy.core.ModelConfig
import dev.buddy.core.ModelReply
import dev.buddy.core.ToolCall
import dev.buddy.core.model.ChatModel
import dev.buddy.phone.FixtureScreenService
import dev.buddy.qualification.FixtureTakeoverCheck
import dev.buddy.qualification.FixtureTakeoverControl
import dev.buddy.qualification.FixtureTakeoverPhase
import dev.buddy.qualification.ModelFixtureCheck
import dev.buddy.qualification.PhoneQualificationActivity
import dev.buddy.shell.IBuddyHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundFixtureDeviceTest {
    private suspend fun withForeground(
        action: suspend (PhoneQualificationActivity, IBuddyHelper, UiAutomation) -> Unit
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use { it.readNBytes(4096).toString(Charsets.UTF_8).trim() }
        val services = shell("settings get secure enabled_accessibility_services")
        val enabled = shell("settings get secure accessibility_enabled")
        require(services == "null" || services.matches(Regex("[A-Za-z0-9_.$/:]*")))
        require(enabled in setOf("null", "0", "1"))
        val component = "${context.packageName}/dev.buddy.phone.FixtureScreenService"
        val selected = services.takeUnless { it == "null" || it.isBlank() }?.let { "$it:$component" } ?: component
        var activity: PhoneQualificationActivity? = null
        try {
            shell("settings put secure enabled_accessibility_services $selected")
            shell("settings put secure accessibility_enabled 1")
            withTimeout(5_000) { while (!FixtureScreenService.connected) delay(50) }
            AuthenticatedHelperFixture.start(context, automation).use { bootstrap ->
                val opened = instrumentation.startActivitySync(
                    Intent(context, PhoneQualificationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ) as PhoneQualificationActivity
                activity = opened
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) { opened.hasWindowFocus() }) delay(50)
                }
                action(opened, bootstrap.helper, automation)
            }
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main) { activity?.finish() }
                if (services == "null") {
                    shell("settings delete secure enabled_accessibility_services")
                } else {
                    shell("settings put secure enabled_accessibility_services $services")
                }
                if (enabled == "null") {
                    shell("settings delete secure accessibility_enabled")
                } else {
                    shell("settings put secure accessibility_enabled $enabled")
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun foregroundProgramKeepsPhysicalTypingScopedAndSuppressesSecureContent() = runBlocking {
        withForeground { opened, helper, automation ->
            val started = CompletableDeferred<Unit>()
            val check = async(Dispatchers.Default) {
                opened.runConnectedCheck(helper) { stage ->
                    if (stage == "Checking private Unicode edit 1 of 3") started.complete(Unit)
                    if (stage == "Checking protected-page suppression") {
                        val preview = withContext(Dispatchers.Main) { requireNotNull(opened.preview) }
                        assertFalse(preview.hidden)
                        assertTrue(preview.text.orEmpty().contains("こんにちは"))
                    }
                }
            }
            withTimeout(10_000) { started.await() }
            while (check.isActive) {
                ParcelFileDescriptor.AutoCloseInputStream(
                    automation.executeShellCommand("input -d 0 text a")
                ).use { it.readNBytes(4096) }
                delay(100)
            }
            assertTrue(check.await().concurrentTypingObserved)
            withContext(Dispatchers.Main) {
                org.junit.Assert.assertNull(opened.preview)
                val text = opened.findViewById<EditText>(android.R.id.edit).text
                assertTrue(text.isNotEmpty() && text.all { it == 'a' })
            }
        }
    }

    @Test(timeout = 60_000)
    fun boundedModelLoopVerifiesThePrivateDraftWithoutReadingThePhysicalField() = runBlocking {
        withForeground { opened, helper, _ ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            withContext(Dispatchers.Main) {
                opened.findViewById<EditText>(android.R.id.edit).apply {
                    setText("physical-field-private-canary")
                    requestFocus()
                }
            }
            var turn = 0
            val model = object : ChatModel {
                override suspend fun complete(
                    config: ModelConfig,
                    messages: List<ChatMessage>,
                    tools: List<JsonObject>
                ): ModelReply {
                    assertFalse(messages.any { it.content.orEmpty().contains("physical-field-private-canary") })
                    val name = when (turn++) {
                        0 -> "fixture_observe"
                        1 -> "fixture_set_test_draft"
                        else -> "end_task"
                    }
                    val arguments = if (name == "end_task") {
                        val result = Json.parseToJsonElement(requireNotNull(messages.last().content)).jsonObject
                        buildJsonObject {
                            put("outcome", "Controlled draft set")
                            put("evidence_ids", result.getValue("evidence_ids") as JsonArray)
                        }.toString()
                    } else {
                        "{}"
                    }
                    return ModelReply(
                        ChatMessage(
                            "assistant",
                            toolCalls = listOf(
                                ToolCall("model-fixture-$turn", function = FunctionCall(name, arguments))
                            )
                        ),
                        10,
                        "tool_calls"
                    )
                }
            }
            ModelFixtureCheck.run(
                context,
                helper,
                ModelConfig("https://fixture-model.invalid/v1", "native-fixture", ""),
                model,
                visible = opened::isCheckVisible,
                refreshAuthorization = { error("The deterministic fixture should finish before renewal") },
                stopTransport = {},
                stage = {}
            )
            assertEquals(3, turn)
            withContext(Dispatchers.Main) {
                assertEquals(
                    "physical-field-private-canary",
                    opened.findViewById<EditText>(android.R.id.edit).text.toString()
                )
            }
        }
    }

    @Test(timeout = 45_000)
    fun nativeTakeoverPreservesTheUserEditAndReturnsTheExistingTask() = runBlocking {
        withForeground { _, helper, automation ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val flags = automation.serviceInfo.flags
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val phase = MutableStateFlow(FixtureTakeoverPhase.PREPARING)
            val control = FixtureTakeoverControl()
            val check = async(Dispatchers.Default) {
                FixtureTakeoverCheck.run(
                    context,
                    helper,
                    control,
                    admitted = { true },
                    refreshAuthorization = { error("The controlled handover should finish before renewal") },
                    stopTransport = {},
                    phase = { phase.value = it },
                    preview = {}
                )
            }
            fun editor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.packageName?.toString() != "dev.buddy.fixture") return null
                if (node.viewIdResourceName == "dev.buddy.fixture:id/fixture_draft_input") return node
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { editor(it)?.let { found -> return found } }
                }
                return null
            }
            try {
                withTimeout(15_000) {
                    phase.first { it == FixtureTakeoverPhase.READY }
                    assertTrue(control.request(false))
                    phase.first { it == FixtureTakeoverPhase.HUMAN }
                    val physical = withTimeout(5_000) {
                        while (true) {
                            val found = automation.windowsOnAllDisplays.get(0).orEmpty().firstNotNullOfOrNull {
                                it.root?.let(::editor)
                            }
                            if (found != null) return@withTimeout found
                            delay(50)
                        }
                        error("Moved fixture missing")
                    }
                    assertTrue(physical.text.toString().startsWith("Private takeover check"))
                    assertTrue(
                        physical.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "human")
                            }
                        )
                    )
                    assertTrue(control.request(true))
                    assertTrue(check.await())
                    assertEquals(FixtureTakeoverPhase.CLOSED, phase.value)
                    assertFalse(control.request(true))
                }
            } finally {
                withContext(NonCancellable) {
                    check.cancel()
                    check.join()
                    ParcelFileDescriptor.AutoCloseInputStream(
                        automation.executeShellCommand("am force-stop dev.buddy.fixture")
                    ).use { it.readNBytes(4096) }
                    automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun modelPauseCancelsTheProviderAndResumesAfterHumanReturnWithoutSharingCustomText() = runBlocking {
        withForeground { _, helper, automation ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val flags = automation.serviceInfo.flags
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            val control = FixtureTakeoverControl()
            val phase = MutableStateFlow(FixtureTakeoverPhase.PREPARING)
            val pendingModel = CompletableDeferred<Unit>()
            var cancelled = false
            var turn = 0
            val model = object : ChatModel {
                override suspend fun complete(
                    config: ModelConfig,
                    messages: List<ChatMessage>,
                    tools: List<JsonObject>
                ): ModelReply {
                    assertFalse(messages.any { it.content.orEmpty().contains("human-private-model-canary") })
                    val current = turn++
                    if (current == 1) {
                        pendingModel.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    }
                    val name = when (current) {
                        0, 2 -> "fixture_observe"
                        3 -> "fixture_set_test_draft"
                        else -> "end_task"
                    }
                    val arguments = if (name == "end_task") {
                        val result = Json.parseToJsonElement(requireNotNull(messages.last().content)).jsonObject
                        buildJsonObject {
                            put("outcome", "Controlled draft verified after handover")
                            put("evidence_ids", result.getValue("evidence_ids") as JsonArray)
                        }.toString()
                    } else {
                        "{}"
                    }
                    return ModelReply(
                        ChatMessage(
                            "assistant",
                            toolCalls = listOf(
                                ToolCall(
                                    "human-model-$current",
                                    function = FunctionCall(name, arguments)
                                )
                            )
                        ),
                        10,
                        "tool_calls"
                    )
                }
            }
            val check = async(Dispatchers.Default) {
                ModelFixtureCheck.run(
                    context, helper,
                    ModelConfig("https://no-live-provider.invalid/v1", "fixture", ""), model,
                    visible = { true }, refreshAuthorization = { error("The native round trip exceeded renewal") },
                    stopTransport = {}, stage = {}, control = control, phase = { phase.value = it }
                )
            }
            fun editor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.packageName?.toString() != "dev.buddy.fixture") return null
                if (node.viewIdResourceName == "dev.buddy.fixture:id/fixture_draft_input") return node
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { editor(it)?.let { found -> return found } }
                }
                return null
            }
            try {
                withTimeout(15_000) {
                    pendingModel.await()
                    assertTrue(control.request(false))
                    phase.first { it == FixtureTakeoverPhase.HUMAN }
                    assertTrue(cancelled)
                    val physical = withTimeout(5_000) {
                        while (true) {
                            val found = automation.windowsOnAllDisplays.get(0).orEmpty().firstNotNullOfOrNull {
                                it.root?.let(::editor)
                            }
                            if (found != null) return@withTimeout found
                            delay(50)
                        }
                        error("Human fixture missing")
                    }
                    assertTrue(
                        physical.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    "human-private-model-canary"
                                )
                            }
                        )
                    )
                    assertTrue(control.request(true))
                    check.await()
                    assertEquals(5, turn)
                    assertEquals(FixtureTakeoverPhase.CLOSED, phase.value)
                }
            } finally {
                withContext(NonCancellable) {
                    check.cancel()
                    check.join()
                    ParcelFileDescriptor.AutoCloseInputStream(
                        automation.executeShellCommand("am force-stop dev.buddy.fixture")
                    ).use { it.readNBytes(4096) }
                    automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                }
            }
        }
    }
}
