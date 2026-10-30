package dev.buddy.adb

import dev.buddy.core.AdbPacket
import dev.buddy.core.LoopbackPort
import dev.buddy.core.ProtocolException
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class AdbTransport(private val identity: AdbIdentity) : Closeable {
    @Volatile private var socket: Socket? = null
    private var maxPayload = 4096

    fun connect(port: LoopbackPort) {
        close()
        val raw = Socket()
        socket = raw
        raw.connect(InetSocketAddress("127.0.0.1", port.value), 5_000)
        raw.soTimeout = 10_000
        raw.tcpNoDelay = true
        send(
            AdbPacket(
                AdbPacket.CNXN,
                0x01000001,
                AdbPacket.MAX_PAYLOAD,
                "host::features=shell_v2;\u0000".toByteArray()
            )
        )
        val first = receive()
        if (first.command !=
            AdbPacket.STLS
        ) {
            throw ProtocolException("Wireless debugging requires paired TLS")
        }
        send(AdbPacket(AdbPacket.STLS, 0x01000000, 0))
        val tls = LocalTls(identity).upgrade(raw, port)
        socket = tls
        tls.startHandshake()
        val connected = receive()
        if (connected.command != AdbPacket.CNXN ||
            connected.arg1 !in 1..AdbPacket.MAX_PAYLOAD
        ) {
            throw ProtocolException("ADB authorization failed")
        }
        maxPayload = connected.arg1
    }

    // Only fixed packaged helper entry points can reach this method. No model shell tool.
    fun bootstrap(command: BootstrapCommand, nonce: ByteArray): String {
        require(nonce.size == 32)
        val local = 1
        send(
            AdbPacket(AdbPacket.OPEN, local, 0, ("shell:" + command.value + "\u0000").toByteArray())
        )
        val opened = receive()
        if (opened.command != AdbPacket.OKAY ||
            opened.arg1 != local
        ) {
            throw ProtocolException("Helper launch rejected")
        }
        val remote = opened.arg0
        send(
            AdbPacket(
                AdbPacket.WRTE,
                local,
                remote,
                (
                    java.util.Base64.getEncoder().encodeToString(nonce) +
                        "\n"
                    ).toByteArray()
            )
        )
        var count = 0
        val result = StringBuilder()
        while (count++ < 128) {
            val packet = receive()
            if (packet.arg0 != remote ||
                packet.arg1 != local
            ) {
                throw ProtocolException("Unexpected helper stream")
            }
            when (packet.command) {
                AdbPacket.OKAY -> Unit

                AdbPacket.WRTE -> {
                    if (packet.payload.size > maxPayload ||
                        result.length + packet.payload.size > 8192
                    ) {
                        throw ProtocolException("Helper output limit")
                    }
                    result.append(packet.payload.toString(StandardCharsets.UTF_8))
                    send(AdbPacket(AdbPacket.OKAY, local, remote))
                    if (result.contains("BUDDY_HANDOFF_OK")) return result.toString()
                }

                AdbPacket.CLSE -> {
                    send(AdbPacket(AdbPacket.CLSE, local, remote))
                    return result.toString()
                }

                else -> throw ProtocolException("Unexpected helper packet")
            }
        }
        throw ProtocolException("Helper launch timed out")
    }

    private fun send(packet: AdbPacket) = packet.writeTo(checkNotNull(socket).getOutputStream())
    private fun receive(): AdbPacket = AdbPacket.readFrom(checkNotNull(socket).getInputStream())
    override fun close() {
        socket?.close()
        socket = null
    }
}

class BootstrapCommand private constructor(val arguments: List<String>) {
    val value: String get() = arguments.joinToString(" ") { "'" + it.replace("'", "'\"'\"'") + "'" }
    companion object {
        fun forInstalledApp(context: android.content.Context): BootstrapCommand {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                )
            )
            val certificate = requireNotNull(info.signingInfo).apkContentsSigners.single()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(certificate.toByteArray()).joinToString("") { "%02x".format(it) }
            return helper(context.applicationInfo.sourceDir, context.packageName, context.applicationInfo.uid, digest)
        }

        fun helper(
            apk: String,
            appId: String,
            appUid: Int,
            signingDigest: String,
            revalidate: Boolean = false
        ): BootstrapCommand {
            require(apk.startsWith("/data/app/") && apk.endsWith(".apk") && !apk.contains('\n'))
            require(appId.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]+")) && appUid >= 10000)
            require(signingDigest.matches(Regex("[0-9a-f]{64}")))
            val entry = if (revalidate) "dev.buddy.helper.RevalidateMain" else "dev.buddy.helper.HelperMain"
            return BootstrapCommand(
                listOf(
                    "env",
                    "CLASSPATH=$apk",
                    "app_process",
                    "/system/bin",
                    entry,
                    appId,
                    appUid.toString(),
                    signingDigest
                )
            )
        }
    }
}
