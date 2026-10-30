package dev.buddy

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.connection.PhoneConnection
import dev.buddy.core.AdbPacket
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionLifecycleDeviceTest {
    @Test fun disconnectAndForgetCloseAStalledConnectionWithoutLateStatusRestoration() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val phone = PhoneConnection(InstrumentationRegistry.getInstrumentation().targetContext)
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val listener = launch(Dispatchers.IO) {
                try {
                    server.accept().use { peer ->
                        assertEquals(AdbPacket.CNXN, AdbPacket.readFrom(peer.inputStream).command)
                        requested.complete(Unit)
                        release.await()
                        runCatching {
                            AdbPacket(AdbPacket.STLS, 0x01000000, 0).writeTo(peer.outputStream)
                        }
                    }
                } catch (failure: IOException) {
                    if (isActive) throw failure
                }
            }
            try {
                val connecting = async(Dispatchers.Default) { phone.connect(server.localPort) }
                withTimeout(3_000) { requested.await() }
                assertTrue(phone.busy.value)
                val stopped = async { phone.disconnect() }
                val forgotten = async { phone.disconnect(forget = true) }
                withTimeout(3_000) {
                    assertTrue(stopped.await())
                    assertTrue(forgotten.await())
                    connecting.await()
                }
                release.complete(Unit)
                listener.join()
                assertFalse(phone.busy.value)
                assertTrue(phone.status.value.startsWith("Local identity removed"))
            } finally {
                withContext(NonCancellable) {
                    release.complete(Unit)
                    listener.cancel()
                    server.close()
                    listener.join()
                    phone.disconnect(forget = true)
                }
            }
        }
    }
}
