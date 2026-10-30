package dev.buddy

import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.adb.BootstrapCommand
import dev.buddy.connection.BootstrapProvider
import dev.buddy.shell.ShellProtocol
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// This tests the UID/process/nonce protocol. Actual daemon authorization and Android Forget
// still require the separate wireless transport probe and physical-device qualification.
@RunWith(AndroidJUnit4::class)
class AuthorizationProofDeviceTest {
    @Test fun onlyANewAuthenticatedShellProcessCanCompleteAOneTimeProof() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        AuthenticatedHelperFixture.start(context, automation).use { fixture ->
            val helper = fixture.helper
            val previous = helper.capabilities().getLong("authorizationDeadline")
            val pending = BootstrapProvider.prepare(ShellProtocol.REVALIDATE, helper)
            val challenge = pending.nonce.copyOf()
            val descriptors = automation.executeShellCommandRwe(
                BootstrapCommand.forInstalledApp(context, revalidate = true).arguments.joinToString(" ")
            )
            try {
                helper.prepareAuthorization(challenge)
                assertThrows(IllegalStateException::class.java) { helper.completeAuthorization(challenge) }
                delay(100)
                ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use {
                    it.write((Base64.encodeToString(challenge, Base64.NO_WRAP) + "\n").toByteArray())
                }
                withTimeout(5_000) {
                    pending.result.await()
                    while (helper.capabilities().getLong("authorizationDeadline") <= previous) delay(25)
                }
                assertTrue(helper.capabilities().getLong("authorizationDeadline") > previous)
                assertThrows(IllegalStateException::class.java) { helper.prepareAuthorization(challenge) }
                // Issuing a new challenge alone cannot change the last successful proof.
                val proved = helper.capabilities().getLong("authorizationDeadline")
                helper.prepareAuthorization(ByteArray(32).also(SecureRandom()::nextBytes))
                assertTrue(helper.capabilities().getLong("authorizationDeadline") == proved)
            } finally {
                challenge.fill(0)
                descriptors.forEach { runCatching { it.close() } }
                BootstrapProvider.clear()
            }
        }
    }
}
