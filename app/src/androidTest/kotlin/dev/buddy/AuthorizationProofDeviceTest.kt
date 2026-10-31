package dev.buddy

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.adb.BootstrapCommand
import dev.buddy.connection.BootstrapProvider
import dev.buddy.qualification.LeaseOwnerService
import dev.buddy.shell.ShellProtocol
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// This tests the UID/process/nonce protocol. Actual daemon authorization and Android Forget
// still require the separate wireless transport probe and physical-device qualification.
@RunWith(AndroidJUnit4::class)
class AuthorizationProofDeviceTest {
    @Test fun ownerProcessDeathReleasesTheDisplayWithoutKillingTheMainAppOrHelper() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connected = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onNullBinding(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("Fixture owner unavailable"))
            }
        }
        val bound = withContext(Dispatchers.Main) {
            context.bindService(Intent(context, LeaseOwnerService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
        check(bound)
        try {
            val owner = withTimeout(5_000) { connected.await() }
            val request = Parcel.obtain(owner)
            val response = Parcel.obtain()
            val ownerPid = try {
                check(owner.transact(IBinder.FIRST_CALL_TRANSACTION, request, response, 0))
                response.readException()
                response.readInt()
            } finally {
                request.recycle()
                response.recycle()
            }
            val mainPid = android.os.Process.myPid()
            check(ownerPid > 1 && ownerPid != mainPid)
            AuthenticatedHelperFixture.start(context, instrumentation.uiAutomation).use { fixture ->
                FixtureFrameSink().use { frames ->
                    val helper = fixture.helper
                    val lease = requireNotNull(
                        helper.acquireLease(owner, ByteArray(32).also(SecureRandom()::nextBytes)).getString("lease")
                    )
                    val token = helper.createDisplay(lease, 720, 1280, 240, frames.surface)
                    val display = helper.displayState(lease, token).getInt("displayId")
                    android.os.Process.killProcess(ownerPid)
                    withTimeout(5_000) {
                        val manager = context.getSystemService(DisplayManager::class.java)
                        while (owner.isBinderAlive || manager.getDisplay(display)?.isValid == true) delay(25)
                    }
                    assertTrue(android.os.Process.myPid() == mainPid)
                    assertTrue(helper.capabilities().getInt("helperUid") == ShellProtocol.SHELL_UID)
                    assertThrows(IllegalStateException::class.java) { helper.displayState(lease, token) }
                }
            }
        } finally {
            withContext(Dispatchers.Main) { context.unbindService(connection) }
        }
    }

    @Test fun helperTerminationReleasesItsDisplayWhileTheAppRemainsAlive() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        AuthenticatedHelperFixture.start(context, automation).use { fixture ->
            FixtureFrameSink().use { frames ->
                val helper = fixture.helper
                val owner = Binder()
                val lease = requireNotNull(
                    helper.acquireLease(owner, ByteArray(32).also(SecureRandom()::nextBytes)).getString("lease")
                )
                val token = helper.createDisplay(lease, 720, 1280, 240, frames.surface)
                val display = helper.displayState(lease, token).getInt("displayId")
                val helperPid = helper.capabilities().getInt("helperPid")
                check(helperPid > 1 && helperPid != android.os.Process.myPid())
                val binder = helper.asBinder()
                val died = CompletableDeferred<Unit>()
                val death = IBinder.DeathRecipient { died.complete(Unit) }
                binder.linkToDeath(death, 0)
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(
                        automation.executeShellCommand("kill -KILL $helperPid")
                    ).use { it.readNBytes(512) }
                    withTimeout(5_000) {
                        died.await()
                        val manager = context.getSystemService(DisplayManager::class.java)
                        while (manager.getDisplay(display)?.isValid == true) delay(25)
                    }
                    assertTrue(!binder.isBinderAlive && owner.isBinderAlive)
                } finally {
                    runCatching { binder.unlinkToDeath(death, 0) }
                }
            }
        }
    }

    @Test fun screenOffRejectsOwnedDisplayAccessUntilTheUserReturns() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use {
            it.readNBytes(512)
            Unit
        }
        AuthenticatedHelperFixture.start(context, automation).use { fixture ->
            FixtureFrameSink().use { frames ->
                val helper = fixture.helper
                val owner = Binder()
                val lease = requireNotNull(
                    helper.acquireLease(owner, ByteArray(32).also(SecureRandom()::nextBytes)).getString("lease")
                )
                val token = helper.createDisplay(lease, 720, 1280, 240, frames.surface)
                val display = helper.displayState(lease, token).getInt("displayId")
                try {
                    shell("input keyevent KEYCODE_SLEEP")
                    val power = context.getSystemService(PowerManager::class.java)
                    withTimeout(3_000) { while (power.isInteractive) delay(25) }
                    assertThrows(IllegalStateException::class.java) { helper.displayState(lease, token) }
                    assertThrows(IllegalStateException::class.java) {
                        helper.tap(lease, token, "screen-off-input", 10, 10)
                    }
                } finally {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("wm dismiss-keyguard")
                }
                withTimeout(3_000) {
                    val keyguard = context.getSystemService(KeyguardManager::class.java)
                    while (!context.getSystemService(PowerManager::class.java).isInteractive ||
                        keyguard.isDeviceLocked || keyguard.isKeyguardLocked
                    ) {
                        delay(25)
                    }
                }
                assertTrue(helper.displayState(lease, token).getInt("displayId") == display)
                assertTrue(owner.isBinderAlive)
            }
        }
    }

    @Test fun leaseRenewalCannotKeepAnExpiredAuthorizationOrDisplayAlive() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        AuthenticatedHelperFixture.start(context, instrumentation.uiAutomation).use { fixture ->
            FixtureFrameSink().use { frames ->
                val helper = fixture.helper
                val owner = Binder()
                val proofDeadline = helper.capabilities().getLong("authorizationDeadline")
                val lease = requireNotNull(
                    helper.acquireLease(owner, ByteArray(32).also(SecureRandom()::nextBytes)).getString("lease")
                )
                val token = helper.createDisplay(lease, 720, 1280, 240, frames.surface)
                val displayId = helper.displayState(lease, token).getInt("displayId")
                assertTrue(displayId > 0)
                delay(5_000)
                helper.renewLease(lease, ByteArray(32).also(SecureRandom()::nextBytes))
                assertTrue(helper.capabilities().getLong("authorizationDeadline") == proofDeadline)
                withTimeout(35_000) {
                    while (SystemClock.elapsedRealtime() <= proofDeadline) delay(100)
                    assertThrows(IllegalStateException::class.java) {
                        helper.renewLease(lease, ByteArray(32).also(SecureRandom()::nextBytes))
                    }
                    // The helper's watchdog releases its surface even without any further app IPC.
                    val manager = context.getSystemService(DisplayManager::class.java)
                    while (manager.getDisplay(displayId)?.isValid == true) delay(50)
                }
                assertTrue(owner.isBinderAlive)
            }
        }
    }

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
                val rejected = runCatching {
                    context.contentResolver.call(
                        Uri.parse("content://${context.packageName}${ShellProtocol.AUTHORITY_SUFFIX}"),
                        ShellProtocol.REVALIDATE,
                        null,
                        Bundle().apply {
                            putInt("protocol", ShellProtocol.VERSION)
                            putByteArray("nonce", challenge)
                        }
                    )
                }.exceptionOrNull()
                assertTrue(rejected is IllegalStateException || rejected is SecurityException)
                assertTrue(!pending.result.isCompleted)
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
