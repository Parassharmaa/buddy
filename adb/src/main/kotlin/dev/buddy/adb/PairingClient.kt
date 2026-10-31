package dev.buddy.adb

import android.net.ssl.SSLSockets
import dev.buddy.core.LoopbackPort
import dev.buddy.core.ProtocolException
import dev.buddy.core.normalizedPairingCode
import dev.buddy.core.readExactly
import dev.buddy.pairing.NativeSpake2
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ScheduledFuture
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Wire format: AOSP android-16.0.0_r1 pairing_connection and pairing_auth.
class PairingClient(private val identity: AdbIdentity) : Closeable {
    @Volatile private var socket: Socket? = null
    private val socketLock = Any()
    private var rawSocket: Socket? = null
    private var closed = false
    private var started = false
    private var expiry: ScheduledFuture<*>? = null

    private fun own(value: Socket) = synchronized(socketLock) {
        if (closed) {
            value.close()
            error("Pairing client is closed")
        }
        if (rawSocket == null) rawSocket = value
        socket = value
    }

    fun pair(port: LoopbackPort, rawCode: CharSequence): String {
        synchronized(socketLock) {
            check(!closed && !started)
            started = true
            expiry = SocketDeadlines.schedule(20_000, ::close)
        }
        val code = try {
            normalizedPairingCode(rawCode)
        } catch (failure: Exception) {
            close()
            throw failure
        }
        var native: NativeSpake2? = null
        var secret = byteArrayOf()
        var key = byteArrayOf()
        var shared = byteArrayOf()
        var exported = byteArrayOf()
        try {
            val tls = LocalTls(identity).connect(port, ::own)
            val exchange = NativeSpake2().also { native = it }
            exported = requireNotNull(SSLSockets.exportKeyingMaterial(tls, "adb-label\u0000", null, 64)) {
                "Pairing TLS connection is no longer authenticated"
            }
            check(exported.size == 64)
            secret = ByteArray(code.size + exported.size)
            code.forEachIndexed { index, c -> secret[index] = c.code.toByte() }
            exported.copyInto(secret, code.size)
            val input = DataInputStream(tls.inputStream)
            val output = DataOutputStream(tls.outputStream)
            fun write(type: Int, bytes: ByteArray) {
                output.writeByte(1)
                output.writeByte(type)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }
            fun read(type: Int, size: Int): ByteArray {
                if (input.readUnsignedByte() != 1 || input.readUnsignedByte() != type ||
                    input.readInt() != size
                ) {
                    throw ProtocolException("Invalid pairing frame")
                }
                return readExactly(input, size)
            }
            write(0, exchange.message(secret))
            shared = exchange.process(read(0, 32))
            // AOSP HKDF-SHA256, empty salt, 16-byte AES key, fixed protocol label.
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            val prk = mac.doFinal(shared)
            try {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                key =
                    mac.doFinal(
                        "adb pairing_auth aes-128-gcm key".toByteArray() + byteArrayOf(1)
                    ).copyOf(16)
            } finally {
                prk.fill(0)
            }
            fun crypt(mode: Int, bytes: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ByteArray(12)))
                doFinal(bytes)
            }
            val peer = ByteArray(8192)
            peer[0] = 0 // ADB_RSA_PUB_KEY
            val publicKey = identity.publicKeyWire()
            require(publicKey.size < peer.size)
            publicKey.copyInto(peer, 1)
            write(1, crypt(Cipher.ENCRYPT_MODE, peer))
            val remote = crypt(Cipher.DECRYPT_MODE, read(1, 8208))
            if (remote.size != 8192 ||
                remote[0].toInt() != 1
            ) {
                throw ProtocolException("Invalid pairing peer")
            }
            val end = remote.indexOf(0, 1).let { if (it == -1) remote.size else it }
            return remote.copyOfRange(1, end).toString(Charsets.UTF_8).take(128)
        } finally {
            code.fill('\u0000')
            secret.fill(0)
            key.fill(0)
            shared.fill(0)
            exported.fill(0)
            native?.close()
            close()
        }
    }

    override fun close() {
        val sockets = synchronized(socketLock) {
            closed = true
            expiry?.cancel(false)
            expiry = null
            listOfNotNull(rawSocket, socket).distinct().also {
                rawSocket = null
                socket = null
            }
        }
        sockets.forEach { runCatching { it.close() } }
    }
}

private fun ByteArray.indexOf(value: Byte, from: Int): Int = (from until size).firstOrNull { this[it] == value } ?: -1
