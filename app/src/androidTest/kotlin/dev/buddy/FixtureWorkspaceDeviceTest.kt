package dev.buddy

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.ActionExecutor
import dev.buddy.core.ActionRecord
import dev.buddy.core.Approval
import dev.buddy.core.DispatchGate
import dev.buddy.core.ExecutionResult
import dev.buddy.core.Observation
import dev.buddy.phone.FixtureScreenService
import dev.buddy.phone.FixtureWorkspace
import dev.buddy.shell.IBuddyHelper
import dev.buddy.storage.EncryptedStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixtureWorkspaceDeviceTest {
    @Test fun appObservationApprovalAndPrivacyUseTheOwnedFixtureWorkspace() = runBlocking {
        verifyWorkspace(secureOnly = false)
    }

    @Test fun secureWindowWithoutAPasswordSuppressesTheOwnedTree() = runBlocking {
        verifyWorkspace(secureOnly = true)
    }
    private suspend fun verifyWorkspace(secureOnly: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context = instrumentation.targetContext
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use { it.readNBytes(4096).toString(Charsets.UTF_8).trim() }
        val originalServices = shell("settings get secure enabled_accessibility_services")
        val originalEnabled = shell("settings get secure accessibility_enabled")
        require(originalServices == "null" || originalServices.matches(Regex("[A-Za-z0-9_.$/:]*")))
        val component = "${context.packageName}/dev.buddy.phone.FixtureScreenService"
        val services = originalServices.takeUnless { it == "null" || it.isBlank() }
            ?.let { "$it:$component" } ?: component
        var stage = "enable_fixture_service"
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            shell("settings put secure enabled_accessibility_services $services")
            shell("settings put secure accessibility_enabled 1")
            withTimeout(5_000) { while (!FixtureScreenService.connected) delay(50) }
            stage = "authenticated_helper"
            AuthenticatedHelperFixture.start(context, automation).use { bootstrap ->
                FixtureFrameSink().use { frames ->
                    EncryptedStore(context).use { store ->
                        store.clear()
                        stage = "open_workspace"
                        val runId = UUID.randomUUID().toString()
                        var verificationRequest: String? = null
                        val verificationTaps = AtomicInteger()
                        val missingMetadata = AtomicInteger()
                        val observedHelper = object : IBuddyHelper by bootstrap.helper {
                            override fun tap(lease: String, token: String, requestId: String, x: Int, y: Int) {
                                bootstrap.helper.tap(lease, token, requestId, x, y)
                                if (requestId == verificationRequest) {
                                    verificationTaps.incrementAndGet()
                                    missingMetadata.set(1)
                                }
                            }
                            override fun displayState(lease: String, token: String): Bundle {
                                val current = bootstrap.helper.displayState(lease, token)
                                return if (missingMetadata.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                                    Bundle(current).apply {
                                        putBoolean("windowMetadataKnown", false)
                                        putInt("windowMetadataStatus", 6)
                                    }
                                } else {
                                    current
                                }
                            }
                        }
                        val workspace = FixtureWorkspace.open(
                            context,
                            observedHelper,
                            ActionExecutor(store, SystemClock::elapsedRealtime),
                            frames.surface,
                            runId,
                            recordEvidence = { observation ->
                                store.put("evidence:${observation.id}", "evidence", Json.encodeToString(observation))
                            }
                        )
                        try {
                            suspend fun ready(predicate: (Observation) -> Boolean): Observation {
                                var last: Observation? = null
                                var lastFailure: IllegalStateException? = null
                                try {
                                    return withTimeout(5_000) {
                                        while (true) {
                                            try {
                                                val observation = workspace.observe()
                                                last = observation
                                                if (predicate(observation)) return@withTimeout observation
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: IllegalStateException) {
                                                lastFailure = failure
                                            }
                                            delay(50)
                                        }
                                        error("Fixture observation unavailable")
                                    }
                                } catch (timeout: TimeoutCancellationException) {
                                    throw AssertionError(
                                        "Fixture not ready; nodes=${last?.nodes?.size}; " +
                                            "private=${last?.credentialSurface}; overlay=${last?.excludedOverlay}; " +
                                            FixtureScreenService.lastWindowSummary,
                                        lastFailure ?: timeout
                                    )
                                }
                            }
                            fun gate() =
                                DispatchGate(false, false, false, true, true, Long.MAX_VALUE, Long.MAX_VALUE, false)
                            stage = "observe_initial_tree"
                            ready { it.nodes.any { node -> node.editable } }
                            if (!secureOnly) {
                                stage = "bounded_agent_fixture"
                                FixtureAgentProbe.verify(workspace, store, runId)
                            }
                            val current = ready { it.nodes.any { node -> node.editable } }
                            val message = "こんにちは Buddy · café"
                            val draft = workspace.prepareDraft(current, message)
                            stage = "dispatch_draft"
                            val draftResult = workspace.execute(draft, gate = { gate() })
                            assertTrue("Draft execution: $draftResult", draftResult is ExecutionResult.Verified)
                            stage = "observe_draft"
                            val drafted = ready { it.nodes.any { node -> node.editable && node.label == message } }
                            stage = "reject_forged_payload"
                            val forged = drafted.copy(
                                nodes = drafted.nodes.map {
                                    if (it.editable) it.copy(label = "forged fixture payload") else it
                                }
                            )
                            assertTrue(runCatching { workspace.prepareSend(forged) }.isFailure)
                            val send = workspace.prepareSend(drafted)
                            stage = "approval_required"
                            assertEquals(ExecutionResult.ApprovalRequired, workspace.execute(send, gate = { gate() }))
                            assertTrue(workspace.observe().nodes.any { it.label == "Nothing sent" })
                            val action = send.proposal
                            val approval = Approval(
                                UUID.randomUUID().toString(),
                                action.runId,
                                action.operationKey,
                                action.fingerprint,
                                SystemClock.elapsedRealtime() + 5_000,
                                accepted = true
                            )
                            verificationRequest = action.id
                            stage = "approve_fixture_send"
                            val sendResult = workspace.execute(send, approval, gate = { gate() })
                            if (sendResult !is ExecutionResult.Verified) {
                                val saved = store.get("action:${action.id}")?.let {
                                    Json.decodeFromString<ActionRecord>(it)
                                }
                                val remaining = bootstrap.helper.capabilities().getLong("authorizationDeadline") -
                                    SystemClock.elapsedRealtime()
                                throw AssertionError(
                                    "Send execution: $sendResult; failure=${saved?.failure}; " +
                                        "elapsedMs=${saved?.elapsedMillis}; authRemainingMs=$remaining; " +
                                        FixtureScreenService.lastWindowSummary
                                )
                            }
                            assertEquals(1, verificationTaps.get())
                            sendResult.evidenceIds.forEach { id ->
                                assertNotNull(store.get("evidence:$id"))
                            }
                            assertEquals("consumed", store.get("consumed:${approval.id}"))
                            stage = "takeover_and_handback"
                            workspace.takeOver()
                            assertTrue(runCatching { workspace.observe() }.isFailure)
                            workspace.returnToWorkspace()
                            val returned = ready { it.nodes.any { node -> node.label == "Sent to Alice: $message" } }
                            assertTrue(returned.fingerprint != send.proposal.fingerprint)
                            assertTrue(workspace.execute(draft, gate = { gate() }) is ExecutionResult.Blocked)
                            assertEquals(
                                ExecutionResult.ApprovalRequired,
                                workspace.execute(workspace.prepareSend(returned), approval, gate = { gate() })
                            )
                            stage = "suppress_protected_tree"
                            val protectedPage = if (secureOnly) {
                                workspace.prepareSecurePage(returned)
                            } else {
                                workspace.prepareCredentialPage(returned)
                            }
                            val protectedResult = workspace.execute(protectedPage, gate = { gate() })
                            assertTrue("Protected page: $protectedResult", protectedResult is ExecutionResult.Verified)
                            val suppressed = workspace.observe()
                            assertTrue(suppressed.nodes.isEmpty())
                            if (secureOnly) {
                                assertTrue(suppressed.protected && !suppressed.credentialSurface)
                            } else {
                                assertTrue(suppressed.credentialSurface && !suppressed.protected)
                            }
                            assertTrue(runCatching { workspace.prepareDraft(suppressed, "blocked") }.isFailure)
                        } finally {
                            workspace.close()
                            store.clear()
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("App fixture workspace failed at $stage", failure)
        } finally {
            if (originalServices == "null" || originalServices.isBlank()) {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $originalServices")
            }
            if (originalEnabled in setOf("0", "1")) {
                shell("settings put secure accessibility_enabled $originalEnabled")
            } else {
                shell("settings delete secure accessibility_enabled")
            }
        }
    }
}
