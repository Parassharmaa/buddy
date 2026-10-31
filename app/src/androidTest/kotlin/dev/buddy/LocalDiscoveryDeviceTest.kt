package dev.buddy

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.adb.AdbServiceKind
import dev.buddy.adb.LocalAdbDiscovery
import dev.buddy.connection.PhoneConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDiscoveryDeviceTest {
    @Test(timeout = 30_000)
    fun locallyAdvertisedPortsAreRoutingHintsAndCannotAuthenticateAFakeDaemon() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NsdManager::class.java)
        val registered = CompletableDeferred<NsdServiceInfo>()
        val accepted = CompletableDeferred<Unit>()
        val callback = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                registered.completeExceptionally(IllegalStateException("Fixture advertisement failed"))
            }
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registered.complete(info)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        val phone = PhoneConnection(context)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val peer = thread(isDaemon = true, name = "buddy-fake-daemon-fixture") {
                try {
                    server.accept().use { }
                    accepted.complete(Unit)
                } catch (_: Exception) { }
            }
            try {
                manager.registerService(
                    NsdServiceInfo().apply {
                        serviceName = "Buddy-fixture-" + UUID.randomUUID().toString()
                        serviceType = AdbServiceKind.PAIRING.serviceType
                        port = server.localPort
                    },
                    NsdManager.PROTOCOL_DNS_SD,
                    context.mainExecutor,
                    callback
                )
                val service = withTimeout(10_000) { registered.await() }
                val found = LocalAdbDiscovery(context).find(AdbServiceKind.PAIRING, service.serviceName)
                assertEquals(listOf(server.localPort), found.map { it.value })
                assertFalse(phone.busy.value)
                phone.connect(found.single().value)
                withTimeout(2_000) { accepted.await() }
                assertFalse(phone.busy.value)
                assertTrue(phone.status.value.startsWith("Connection test failed"))
            } finally {
                runCatching { manager.unregisterService(callback) }
                phone.disconnect(forget = true)
                server.close()
                peer.join(1_000)
            }
        }
    }
}
