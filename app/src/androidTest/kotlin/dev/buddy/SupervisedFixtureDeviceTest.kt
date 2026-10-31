package dev.buddy

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.ModelConfig
import dev.buddy.qualification.FixtureTaskService
import dev.buddy.qualification.PhoneQualificationActivity
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupervisedFixtureDeviceTest {
    @Test(timeout = 45_000)
    fun notificationStopClosesBlockedTransportAndAnOldStopCannotCancelANewRun() = runBlocking {
        assumeTrue(
            Build.VERSION.SDK_INT >= 34 &&
                InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true" &&
                Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone")
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        )
        // These grants exist only in the disposable CI emulator, which is destroyed after the run.
        automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37) {
            automation.grantRuntimePermission(context.packageName, "android.permission.ACCESS_LOCAL_NETWORK")
        }
        val store = (context.applicationContext as BuddyApplication).store
        val previous = store.get("model-config")
        val manager = context.getSystemService(NotificationManager::class.java)
        var activity: PhoneQualificationActivity? = null
        try {
            store.put(
                "model-config",
                "secret",
                Json.encodeToString(ModelConfig("https://no-model-request.invalid/v1", "synthetic", ""))
            )
            val opened = instrumentation.startActivitySync(
                Intent(context, PhoneQualificationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as PhoneQualificationActivity
            activity = opened
            suspend fun foreground() {
                withContext(Dispatchers.Main) {
                    context.startActivity(
                        Intent(context, PhoneQualificationActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) { opened.hasWindowFocus() }) delay(50)
                }
            }
            suspend fun notification(): Notification = withTimeout(5_000) {
                while (true) {
                    val active = manager.activeNotifications.singleOrNull { it.id == FixtureTaskService.NOTIFICATION }
                    if (active != null) return@withTimeout active.notification
                    delay(50)
                }
                error("Notification missing")
            }
            suspend fun stopped() = withTimeout(5_000) {
                FixtureTaskService.state.first { it.id == null }
                while (manager.activeNotifications.any { it.id == FixtureTaskService.NOTIFICATION }) delay(50)
            }
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 5_000
                var oldStop: android.app.PendingIntent? = null
                var oldId: String? = null
                repeat(2) { index ->
                    foreground()
                    val accepted = async(Dispatchers.IO) { server.accept() }
                    assertTrue(withContext(Dispatchers.Main) { FixtureTaskService.start(opened, server.localPort) })
                    withTimeout(5_000) { accepted.await() }.use { socket ->
                        socket.soTimeout = 3_000
                        val current = notification()
                        val id = requireNotNull(FixtureTaskService.state.value.id)
                        assertEquals(Notification.VISIBILITY_PRIVATE, current.visibility)
                        assertTrue(current.flags and Notification.FLAG_LOCAL_ONLY != 0)
                        assertFalse(
                            current.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)
                                .toString().contains("synthetic")
                        )
                        if (index == 1) {
                            assertNotEquals(oldId, id)
                            requireNotNull(oldStop).send()
                            delay(250)
                            assertEquals(id, FixtureTaskService.state.value.id)
                        }
                        ParcelFileDescriptor.AutoCloseInputStream(
                            automation.executeShellCommand("input keyevent KEYCODE_HOME")
                        ).use { it.readNBytes(4096) }
                        delay(250)
                        assertEquals(id, FixtureTaskService.state.value.id)
                        oldId = id
                        oldStop = current.actions.single().actionIntent
                        requireNotNull(oldStop).send()
                        stopped()
                        // Stop must close a transport blocked waiting for the peer, before its 10 s read timeout.
                        withContext(Dispatchers.IO) {
                            val buffer = ByteArray(256)
                            var total = 0
                            while (true) {
                                val count = socket.getInputStream().read(buffer)
                                if (count == -1) break
                                total += count
                                check(total <= 4096)
                            }
                            buffer.fill(0)
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                EngineeringSessions.supervisor.quiesce {}
                withContext(Dispatchers.Main) {
                    context.stopService(Intent(context, FixtureTaskService::class.java))
                    activity?.finish()
                }
                if (previous == null) store.remove("model-config") else store.put("model-config", "secret", previous)
            }
        }
    }
}
