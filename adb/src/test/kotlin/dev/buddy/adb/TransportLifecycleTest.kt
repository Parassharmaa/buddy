package dev.buddy.adb

import dev.buddy.core.LoopbackPort
import org.junit.Assert.assertThrows
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
}
