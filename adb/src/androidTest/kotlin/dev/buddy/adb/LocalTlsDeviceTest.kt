package dev.buddy.adb

import android.annotation.SuppressLint
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.buddy.core.LoopbackPort
import java.net.InetAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

// Loopback-only self-signed test peer. No provider/model HTTPS trust configuration is changed.
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
@RunWith(AndroidJUnit4::class)
class LocalTlsDeviceTest {
    @Test fun engineBackedTlsPresentsAndUsesTheNonExportableClientIdentity() {
        val identity = AdbIdentity()
        val (key, certificate) = identity.load()
        assertNull(key.encoded)
        val manager = object : X509KeyManager {
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
                null
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) =
                if (type == "RSA") "fixture" else null
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = arrayOf("fixture")
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = key
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        val serverContext = SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(manager), arrayOf(trust), null) }
        val peerCertificate = CompletableFuture<ByteArray>()
        try {
            (
                serverContext.serverSocketFactory.createServerSocket(
                    0,
                    1,
                    InetAddress.getByName("127.0.0.1")
                ) as SSLServerSocket
                ).use { server ->
                server.needClientAuth = true
                server.soTimeout = 10_000
                val peer = thread(isDaemon = true, name = "adb-local-tls-fixture") {
                    try {
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 10_000
                            socket.startHandshake()
                            peerCertificate.complete(socket.session.peerCertificates.single().encoded)
                            assertEquals(7, socket.inputStream.read())
                            socket.outputStream.write(9)
                            socket.outputStream.flush()
                        }
                    } catch (failure: Throwable) {
                        peerCertificate.completeExceptionally(failure)
                    }
                }
                LocalTls(identity).connect(LoopbackPort(server.localPort)) {}.use { client ->
                    assertEquals("TLSv1.3", client.session.protocol)
                    client.outputStream.write(7)
                    client.outputStream.flush()
                    assertEquals(9, client.inputStream.read())
                    assertArrayEquals(certificate.encoded, peerCertificate.get(2, TimeUnit.SECONDS))
                }
                peer.join(2_000)
            }
        } finally {
            identity.forget()
        }
    }
}
