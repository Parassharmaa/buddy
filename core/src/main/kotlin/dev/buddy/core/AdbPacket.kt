package dev.buddy.core

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolException(message: String) : java.io.IOException(message)

data class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = byteArrayOf()) {
    fun writeTo(output: OutputStream) {
        require(payload.size <= MAX_PAYLOAD)
        val checksum = payload.sumOf { it.toInt() and 0xff }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(
                command
            ).putInt(arg0).putInt(arg1).putInt(payload.size).putInt(checksum).putInt(command.inv())
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    companion object {
        const val MAX_PAYLOAD = 1024 * 1024
        val CNXN = command("CNXN")
        val STLS = command("STLS")
        val AUTH = command("AUTH")
        val OPEN = command("OPEN")
        val OKAY = command("OKAY")
        val WRTE = command("WRTE")
        val CLSE = command("CLSE")
        private val allowed = setOf(CNXN, STLS, AUTH, OPEN, OKAY, WRTE, CLSE)

        fun readFrom(input: InputStream): AdbPacket {
            val header = ByteBuffer.wrap(readExactly(input, 24)).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val checksum = header.int
            val magic = header.int
            if (command !in allowed || magic != command.inv() ||
                length !in 0..MAX_PAYLOAD
            ) {
                throw ProtocolException("Invalid ADB frame")
            }
            val payload = readExactly(input, length)
            if (checksum != 0 &&
                checksum != payload.sumOf { it.toInt() and 0xff }
            ) {
                throw ProtocolException("ADB checksum mismatch")
            }
            return AdbPacket(command, arg0, arg1, payload)
        }

        fun command(value: String): Int {
            require(value.length == 4 && value.all { it.code in 32..126 })
            return ByteBuffer.wrap(
                value.toByteArray(Charsets.US_ASCII)
            ).order(ByteOrder.LITTLE_ENDIAN).int
        }
    }
}

fun readExactly(input: InputStream, length: Int): ByteArray {
    require(length in 0..AdbPacket.MAX_PAYLOAD)
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = input.read(result, offset, length - offset)
        if (count < 0) throw java.io.EOFException("Truncated frame")
        if (count == 0) throw ProtocolException("Transport made no progress")
        offset += count
    }
    return result
}

@JvmInline
value class LoopbackPort(val value: Int) {
    init {
        require(value in 1..65535) { "Enter a valid local port" }
    }
}

fun normalizedPairingCode(input: CharSequence): CharArray {
    val digits = input.filterNot { it.isWhitespace() }.map { c ->
        require(c in '0'..'9' || c in '\uFF10'..'\uFF19') { "Use the six-digit code from Android" }
        if (c in '\uFF10'..'\uFF19') ('0'.code + c.code - '\uFF10'.code).toChar() else c
    }
    require(digits.size == 6) { "Enter all six digits" }
    return digits.toCharArray()
}
