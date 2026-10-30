package dev.buddy.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {
    @Test fun packetRoundTripAndTruncation() {
        val packet = AdbPacket(AdbPacket.WRTE, 1, 2, "héllo".toByteArray())
        val output = ByteArrayOutputStream()
        packet.writeTo(output)
        val result = AdbPacket.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(packet.command, result.command)
        assertArrayEquals(packet.payload, result.payload)
        for (size in 0 until output.size()) {
            assertThrows(java.io.IOException::class.java) {
                AdbPacket.readFrom(ByteArrayInputStream(output.toByteArray().copyOf(size)))
            }
        }
    }

    @Test fun rejectsOversizedPayloadBeforeAllocation() {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(
                AdbPacket.CNXN
            ).putInt(
                0
            ).putInt(0).putInt(Int.MAX_VALUE).putInt(0).putInt(AdbPacket.CNXN.inv()).array()
        assertThrows(ProtocolException::class.java) {
            AdbPacket.readFrom(ByteArrayInputStream(header))
        }
    }

    @Test fun pairingCodeNormalizesOnlyDigitsAndWhitespace() {
        assertArrayEquals("123456".toCharArray(), normalizedPairingCode("１２３ ４５６"))
        listOf("12345", "1234567", "123x56", "123;56", "١٢٣٤٥٦").forEach { code ->
            assertThrows(IllegalArgumentException::class.java) { normalizedPairingCode(code) }
        }
        listOf(0, -1, 65536).forEach {
            assertThrows(IllegalArgumentException::class.java) { LoopbackPort(it) }
        }
    }

    @Test fun endpointsRequireSecureOrExplicitLocalTransport() {
        assertEquals(
            "https://example.com/v1/chat/completions",
            ModelEndpoint.parse("https://example.com/v1").chatUrl
        )
        listOf(
            "http://example.com",
            "https://user:secret@example.com",
            "file:///etc/passwd",
            "https://example.com?key=a",
            "http://10.evil.com",
            "http://127.0.0.1.evil.com",
            "http://010.0.0.1"
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { ModelEndpoint.parse(it, true) }
        }
        assertEquals(
            "http://192.168.1.2:8080/v1/chat/completions",
            ModelEndpoint.parse("http://192.168.1.2:8080/v1", true).chatUrl
        )
        assertThrows(IllegalArgumentException::class.java) {
            ModelEndpoint.parse("http://127.0.0.1")
        }
    }
}
