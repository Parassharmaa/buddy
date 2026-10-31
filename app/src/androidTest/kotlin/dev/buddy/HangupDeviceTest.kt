package dev.buddy

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.shell.ShellProtocol
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HangupDeviceTest {
    @Test fun fixedBootstrapKeepsTheAuthenticatedHelperAliveAfterHangup() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        AuthenticatedHelperFixture.start(instrumentation.targetContext, automation, viaShell = true).use { fixture ->
            val pid = fixture.helper.capabilities().getInt("helperPid")
            check(pid > 0 && pid != android.os.Process.myPid())
            // Controlled shell-owned test process only; this is the signal adbd sends on closure.
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("kill -HUP $pid")).use {
                it.readNBytes(512)
            }
            delay(250)
            assertEquals(ShellProtocol.SHELL_UID, fixture.helper.capabilities().getInt("helperUid"))
        }
    }
}
