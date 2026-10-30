package dev.buddy.adb

import android.annotation.SuppressLint
import dev.buddy.core.LoopbackPort
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import org.conscrypt.Conscrypt

// AOSP ADB accepts server certificates. This is intentionally limited to loopback;
// real shell-UID nonce authentication remains mandatory before any phone tool.
internal class LocalTls(identity: AdbIdentity) {
    private val context: SSLContext
    init {
        val (privateKey, certificate) = identity.load()
        val manager = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
                if (types?.contains("RSA") ==
                    true
                ) {
                    "buddy"
                } else {
                    null
                }
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = if (type ==
                "RSA"
            ) {
                arrayOf("buddy")
            } else {
                null
            }
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias ==
                "buddy"
            ) {
                arrayOf(certificate)
            } else {
                null
            }
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias ==
                "buddy"
            ) {
                privateKey
            } else {
                null
            }
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                null
        }
        context = SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(arrayOf(manager), arrayOf(LoopbackAdbTrust()), SecureRandom())
        }
    }

    fun connect(port: LoopbackPort, onSocket: (Socket) -> Unit): SSLSocket {
        val raw = Socket()
        onSocket(raw)
        try {
            raw.connect(InetSocketAddress("127.0.0.1", port.value), 5_000)
            return upgrade(raw, port).also {
                onSocket(it)
                it.startHandshake()
            }
        } catch (failure: Exception) {
            raw.close()
            throw failure
        }
    }

    fun upgrade(raw: Socket, port: LoopbackPort): SSLSocket = (
        context.socketFactory.createSocket(
            raw,
            "127.0.0.1",
            port.value,
            true
        ) as SSLSocket
        ).apply {
        enabledProtocols = arrayOf("TLSv1.3")
        soTimeout = 10_000
        tcpNoDelay = true
    }

    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private class LoopbackAdbTrust : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    }
}
