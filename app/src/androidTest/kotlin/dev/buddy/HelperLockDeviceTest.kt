package dev.buddy

import android.app.KeyguardManager
import android.app.UiAutomation
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HelperLockDeviceTest {
    @Test(timeout = 45_000)
    fun screenOffRejectsObservationInputAndTakeoverUntilTheUserReturns() = runBlocking {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true" &&
                Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone")
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use { it.readNBytes(4096) }
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        suspend fun unlocked() = withTimeout(5_000) {
            while (!power.isInteractive || keyguard.isDeviceLocked || keyguard.isKeyguardLocked) delay(50)
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        unlocked()
        AuthenticatedHelperFixture.start(context, automation).use { bootstrap ->
            FixtureFrameSink().use { frames ->
                val helper = bootstrap.helper
                val challenge = ByteArray(32).also(SecureRandom()::nextBytes)
                val lease = try {
                    requireNotNull(helper.acquireLease(Binder(), challenge).getString("lease"))
                } finally {
                    challenge.fill(0)
                }
                try {
                    val token = helper.createDisplay(lease, 720, 1280, 240, frames.surface)
                    val display = helper.launchFixture(lease, token, UUID.randomUUID().toString())
                    assertTrue(display > 0)
                    helper.displayState(lease, token)
                    shell("input keyevent KEYCODE_SLEEP")
                    withTimeout(5_000) { while (power.isInteractive) delay(50) }
                    assertThrows(IllegalStateException::class.java) { helper.displayState(lease, token) }
                    assertThrows(IllegalStateException::class.java) {
                        helper.tap(lease, token, UUID.randomUUID().toString(), 10, 10)
                    }
                    assertThrows(IllegalStateException::class.java) {
                        helper.takeOverFixture(lease, token, UUID.randomUUID().toString())
                    }
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                    unlocked()
                    // Reading again is an explicit fresh observation; no suspended action is replayed.
                    helper.displayState(lease, token)
                } finally {
                    withContext(NonCancellable) {
                        shell("input keyevent KEYCODE_WAKEUP")
                        shell("wm dismiss-keyguard")
                        helper.releaseLease(lease)
                    }
                }
            }
        }
    }
}
