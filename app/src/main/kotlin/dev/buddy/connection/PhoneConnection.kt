package dev.buddy.connection

import android.content.Context
import dev.buddy.adb.AdbIdentity
import dev.buddy.adb.AdbTransport
import dev.buddy.adb.BootstrapCommand
import dev.buddy.adb.PairingClient
import dev.buddy.core.LoopbackPort
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class PhoneConnection(private val context: Context) {
    private val identity = AdbIdentity()
    private val _status = MutableStateFlow("Not connected")
    val status = _status.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val stateLock = Any()
    private val operations = Mutex()
    private var stopping: CompletableDeferred<Boolean>? = null
    private var forgetRequested = false
    private val generation = AtomicLong()
    private val disconnecting = AtomicBoolean()
    private var pairing: PairingClient? = null
    private var transport: AdbTransport? = null
    private var helper: IBuddyHelper? = null
    private var pending: BootstrapProvider.Pending? = null

    private fun current(ticket: Long) = generation.get() == ticket && !disconnecting.get()

    private inline fun update(ticket: Long, block: () -> Unit): Boolean = synchronized(stateLock) {
        if (!current(ticket)) {
            false
        } else {
            block()
            true
        }
    }

    suspend fun pair(port: Int, code: CharSequence) {
        val ticket = generation.get()
        if (!current(ticket) || !operations.tryLock()) return
        try {
            withContext(Dispatchers.IO) {
                val client = PairingClient(identity)
                if (!update(ticket) {
                        pairing = client
                        _busy.value = true
                        _status.value = "Pairing with this phone"
                    }
                ) {
                    client.close()
                    return@withContext
                }
                try {
                    client.pair(LoopbackPort(port), code)
                    currentCoroutineContext().ensureActive()
                    update(ticket) {
                        _status.value = "Paired. Enter the separate connection port to finish connecting."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    update(ticket) {
                        _status.value =
                            "Pairing failed. Keep Android’s pairing dialog open and check the current code and pairing port."
                    }
                } finally {
                    client.close()
                    synchronized(stateLock) { if (pairing === client) pairing = null }
                }
            }
        } catch (cancelled: CancellationException) {
            update(ticket) { _status.value = "Pairing cancelled. Check Android’s paired devices before retrying." }
            throw cancelled
        } finally {
            update(ticket) { _busy.value = false }
            operations.unlock()
        }
    }

    suspend fun connect(port: Int) {
        val ticket = generation.get()
        if (!current(ticket) || !operations.tryLock()) return
        var connectedDuringAttempt: IBuddyHelper? = null
        try {
            withContext(Dispatchers.IO) {
                // Binder liveness is not proof that the bounded debugging authorization is still valid.
                // An explicit reconnect revokes the previous helper and performs a new daemon handshake.
                val previous = synchronized(stateLock) { helper.also { helper = null } }
                previous?.let { runCatching { it.disconnect() } }
                currentCoroutineContext().ensureActive()
                val client = AdbTransport(identity)
                if (!update(ticket) {
                        transport = client
                        _busy.value = true
                        _status.value = "Connecting to local debugging"
                    }
                ) {
                    client.close()
                    return@withContext
                }
                var bootstrap: BootstrapProvider.Pending? = null
                var retained = false
                try {
                    client.connect(LoopbackPort(port))
                    val selected = synchronized(stateLock) {
                        if (!current(ticket)) {
                            null
                        } else {
                            _status.value = "Authenticating the phone helper"
                            BootstrapProvider.prepare(ShellProtocol.HANDOFF).also { pending = it }
                        }
                    } ?: return@withContext
                    bootstrap = selected
                    val challenge = selected.nonce.copyOf()
                    try {
                        client.bootstrap(BootstrapCommand.forInstalledApp(context), challenge)
                    } finally {
                        challenge.fill(0)
                    }
                    val connected = requireNotNull(withTimeout(5_000) { selected.result.await() })
                    connectedDuringAttempt = connected
                    val caps = connected.capabilities()
                    check(caps.getInt("protocol") == ShellProtocol.VERSION)
                    check(caps.getInt("helperUid") == ShellProtocol.SHELL_UID)
                    currentCoroutineContext().ensureActive()
                    connected.asBinder().linkToDeath({
                        synchronized(stateLock) {
                            if (helper === connected && current(ticket)) {
                                helper = null
                                _status.value = "Helper stopped. Reconnect to run diagnostics."
                            }
                        }
                    }, 0)
                    update(ticket) {
                        check(connected.asBinder().isBinderAlive)
                        helper = connected
                        retained = true
                        _status.value =
                            "Helper authenticated. Phone actions are disabled until " +
                            "focus, privacy and revocation qualification passes."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    update(ticket) {
                        _status.value =
                            "Connection test failed. Check wireless debugging, the connection port and pairing authorization."
                    }
                } finally {
                    client.close()
                    BootstrapProvider.clear()
                    // A callback can complete just as awaiting it is cancelled. Never leave that helper behind.
                    if (!retained) bootstrap?.deliveredHelper?.let { runCatching { it.disconnect() } }
                    synchronized(stateLock) {
                        if (transport === client) transport = null
                        if (pending === bootstrap) pending = null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                val connected = connectedDuringAttempt
                synchronized(stateLock) { if (helper === connected) helper = null }
                connected?.let { runCatching { it.disconnect() } }
            }
            update(ticket) { _status.value = "Connection cancelled" }
            throw cancelled
        } finally {
            update(ticket) { _busy.value = false }
            operations.unlock()
        }
    }

    suspend fun disconnect(forget: Boolean = false): Boolean {
        var startsCleanup = false
        val completion = synchronized(stateLock) {
            forgetRequested = forgetRequested || forget
            stopping ?: CompletableDeferred<Boolean>().also {
                stopping = it
                disconnecting.set(true)
                generation.incrementAndGet()
                _busy.value = true
                _status.value = "Disconnecting"
                startsCleanup = true
            }
        }
        if (!startsCleanup) return withContext(NonCancellable) { completion.await() }
        return withContext(NonCancellable + Dispatchers.IO) {
            BootstrapProvider.clear()
            val resources = synchronized(stateLock) {
                val stopped = Triple(
                    listOfNotNull(helper, pending?.deliveredHelper).distinctBy { it.asBinder() },
                    pairing,
                    transport
                )
                helper = null
                pending = null
                pairing = null
                transport = null
                stopped
            }
            // Revoke phone control first; network shutdown and optional key removal follow.
            resources.first.forEach { runCatching { it.disconnect() } }
            resources.second?.close()
            resources.third?.close()
            var removed = true
            var forgot = false
            operations.withLock {
                while (true) {
                    val removeIdentity = synchronized(stateLock) {
                        if (forgetRequested) {
                            forgetRequested = false
                            true
                        } else {
                            _status.value = when {
                                !removed -> "Phone disconnected. Local identity removal failed; try Forget again."

                                forgot ->
                                    "Local identity removed. Also forget Buddy in " +
                                        "Android’s Wireless debugging → Paired devices."

                                else -> "Disconnected. Phone control is stopped."
                            }
                            _busy.value = false
                            stopping = null
                            disconnecting.set(false)
                            false
                        }
                    }
                    if (!removeIdentity) break
                    try {
                        identity.forget()
                        forgot = true
                        removed = true
                    } catch (_: Exception) {
                        removed = false
                    }
                }
            }
            completion.complete(removed)
            removed
        }
    }
}
