package dev.buddy.connection

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import dev.buddy.adb.AdbIdentity
import dev.buddy.adb.AdbTransport
import dev.buddy.adb.BootstrapCommand
import dev.buddy.adb.PairingClient
import dev.buddy.core.LoopbackPort
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PhoneConnection(private val context: Context) {
    private val identity = AdbIdentity()
    private val _status = MutableStateFlow("Not connected")
    val status = _status.asStateFlow()

    @Volatile private var pairing: PairingClient? = null

    @Volatile private var transport: AdbTransport? = null

    @Volatile private var helper: IBuddyHelper? = null
    private val owner = Binder()

    suspend fun pair(port: Int, code: CharSequence) = withContext(Dispatchers.IO) {
        _status.value = "Pairing with this phone"
        val client = PairingClient(identity)
        pairing = client
        try {
            client.pair(LoopbackPort(port), code)
            _status.value = "Paired. Enter the separate connection port to finish connecting."
        } catch (_: Exception) {
            _status.value =
                "Pairing failed. Keep Android’s pairing dialog open and check the current code and pairing port."
        } finally {
            client.close()
            pairing = null
        }
    }

    suspend fun connect(port: Int) = withContext(Dispatchers.IO) {
        if (helper?.asBinder()?.isBinderAlive ==
            true
        ) {
            _status.value = "Helper already connected; device qualification is pending"
            return@withContext
        }
        _status.value = "Connecting to local debugging"
        val client = AdbTransport(identity)
        transport = client
        try {
            client.connect(LoopbackPort(port))
            _status.value = "Authenticating the phone helper"
            val pending = BootstrapProvider.prepare(ShellProtocol.HANDOFF)
            val challenge = pending.nonce.copyOf()
            try {
                client.bootstrap(BootstrapCommand.forInstalledApp(context), challenge)
            } finally {
                challenge.fill(0)
            }
            val connected = requireNotNull(withTimeout(5_000) { pending.result.await() })
            val caps = connected.capabilities()
            check(
                caps.getInt("protocol") == ShellProtocol.VERSION && caps.getInt("helperUid") == ShellProtocol.SHELL_UID
            )
            connected.asBinder().linkToDeath({
                helper = null
                _status.value =
                    "Helper stopped. Reconnect to run diagnostics."
            }, 0)
            helper = connected
            _status.value =
                "Helper authenticated. Phone actions are disabled until " +
                "focus, privacy and revocation qualification passes."
        } catch (_: Exception) {
            _status.value =
                "Connection test failed. Check wireless debugging, the connection port and pairing authorization."
        } finally {
            client.close()
            transport = null
            BootstrapProvider.clear()
        }
    }

    suspend fun disconnect(forget: Boolean = false) = withContext(Dispatchers.IO) {
        pairing?.close()
        transport?.close()
        BootstrapProvider.clear()
        helper?.let { runCatching { it.disconnect() } }
        helper = null
        if (forget) identity.forget()
        _status.value =
            if (forget) {
                "Local identity removed. Also forget Buddy in Android’s Wireless debugging → Paired devices."
            } else {
                "Disconnected. Phone control is stopped."
            }
    }
}
