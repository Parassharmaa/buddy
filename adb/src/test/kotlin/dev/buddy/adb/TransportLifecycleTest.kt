package dev.buddy.adb

import dev.buddy.core.AdbPacket
import dev.buddy.core.LoopbackPort
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportLifecycleTest {
    @Test fun closedTransportCannotStartOrReopenAConnection() {
        val transport = AdbTransport(AdbIdentity())
        transport.close()
        transport.close()
        assertThrows(IllegalStateException::class.java) { transport.connect(LoopbackPort(1)) }
    }

    @Test fun closedPairingClientCannotLoadAnIdentityOrOpenASocket() {
        val client = PairingClient(AdbIdentity())
        client.close()
        client.close()
        assertThrows(IllegalStateException::class.java) { client.pair(LoopbackPort(1), "123456") }
    }

    @Test fun byteProgressCannotKeepAnUnauthenticatedConnectionOpenIndefinitely() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val finished = AtomicBoolean()
            val peer = thread(isDaemon = true, name = "adb-drip-fixture") {
                try {
                    server.accept().use { socket ->
                        AdbPacket.readFrom(socket.inputStream)
                        val reply = ByteArrayOutputStream().also {
                            AdbPacket(AdbPacket.STLS, 0x01000000, 0).writeTo(it)
                        }.toByteArray()
                        for (byte in reply) {
                            if (finished.get()) break
                            socket.outputStream.write(byte.toInt())
                            socket.outputStream.flush()
                            Thread.sleep(100)
                        }
                    }
                } catch (_: IOException) {
                    // Expected when the client's absolute deadline closes its socket.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            AdbTransport(AdbIdentity(), 500).use { client ->
                try {
                    val start = System.nanoTime()
                    assertThrows(IOException::class.java) { client.connect(LoopbackPort(server.localPort)) }
                    assertTrue("Peer progress bypassed the deadline", (System.nanoTime() - start) / 1_000_000 < 2000)
                } finally {
                    finished.set(true)
                    server.close()
                    peer.interrupt()
                    peer.join(1000)
                }
            }
        }
    }
}
