package dev.buddy

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.adb.BootstrapCommand
import dev.buddy.connection.BootstrapProvider
import dev.buddy.shell.ShellProtocol
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootstrapIdentityDeviceTest {
    @Test fun mismatchedAppUidAndCertificateAreRejectedBeforeReadingTheNonce() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val digest = BootstrapCommand.forInstalledApp(context).arguments.last()
        val changedDigest = (if (digest.first() == '0') "1" else "0") + digest.drop(1)
        val commands = listOf(
            BootstrapCommand.helper(
                context.applicationInfo.sourceDir,
                context.packageName,
                context.applicationInfo.uid + 1,
                digest
            ),
            BootstrapCommand.helper(
                context.applicationInfo.sourceDir,
                context.packageName,
                context.applicationInfo.uid,
                changedDigest
            )
        )
        commands.forEach { command ->
            check(command.arguments.none { it.any(Char::isWhitespace) })
            val pending = BootstrapProvider.prepare(ShellProtocol.HANDOFF)
            val descriptors = instrumentation.uiAutomation.executeShellCommandRwe(command.arguments.joinToString(" "))
            try {
                // Keep stdin open without sending a nonce: package identity must be checked first.
                val ready = StructPollfd().apply {
                    fd = descriptors[2].fileDescriptor
                    events = OsConstants.POLLIN.toShort()
                }
                assertTrue("Identity rejection did not arrive", Os.poll(arrayOf(ready), 15_000) > 0)
                val buffer = ByteArray(1024)
                val count = Os.read(descriptors[2].fileDescriptor, buffer, 0, buffer.size)
                val reason = if (count > 0) String(buffer, 0, count, Charsets.UTF_8) else ""
                assertTrue(
                    "Bootstrap did not reject the mismatched package identity",
                    reason.lineSequence().any {
                        it == "BUDDY_BOOTSTRAP_FAILED:package_identity:IllegalStateException"
                    }
                )
                assertFalse(pending.result.isCompleted)
            } finally {
                pending.deliveredHelper?.let { runCatching { it.disconnect() } }
                descriptors.forEach { runCatching { it.close() } }
                BootstrapProvider.clear()
            }
        }
    }
}
