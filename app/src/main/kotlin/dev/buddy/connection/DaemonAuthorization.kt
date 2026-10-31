package dev.buddy.connection

import android.content.Context
import android.os.SystemClock
import dev.buddy.adb.AdbIdentity
import dev.buddy.adb.AdbTransport
import dev.buddy.adb.BootstrapCommand
import dev.buddy.core.LoopbackPort
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// The caller serializes bootstrap operations and owns the socket for immediate Stop/disconnect.
internal class DaemonAuthorization(private val context: Context, private val identity: AdbIdentity) {
    suspend fun refresh(
        helper: IBuddyHelper,
        port: LoopbackPort,
        onOpen: (AdbTransport) -> Unit,
        onClose: (AdbTransport) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val before = helper.capabilities()
        check(before.getInt("protocol") == ShellProtocol.VERSION)
        check(before.getInt("helperUid") == ShellProtocol.SHELL_UID)
        check(before.getInt("helperPid") > 0)
        check(before.getLong("authorizationDeadline") > SystemClock.elapsedRealtime())
        val pending = BootstrapProvider.prepare(ShellProtocol.REVALIDATE, helper)
        val challenge = pending.nonce.copyOf()
        val transport = AdbTransport(identity, timeoutMs = 10_000)
        try {
            onOpen(transport)
            currentCoroutineContext().ensureActive()
            helper.prepareAuthorization(challenge)
            // A new client always creates a new TLS context and a new shell proof process.
            transport.connect(port)
            currentCoroutineContext().ensureActive()
            transport.bootstrap(BootstrapCommand.forInstalledApp(context, revalidate = true), challenge)
            withTimeout(5_000) { pending.result.await() }
            currentCoroutineContext().ensureActive()
            val after = helper.capabilities()
            check(after.getInt("protocol") == ShellProtocol.VERSION)
            check(after.getInt("helperUid") == ShellProtocol.SHELL_UID)
            check(after.getInt("helperPid") == before.getInt("helperPid"))
            val deadline = after.getLong("authorizationDeadline")
            check(deadline > before.getLong("authorizationDeadline"))
            check(deadline - SystemClock.elapsedRealtime() in 1..ShellProtocol.LEASE_MS)
            deadline
        } finally {
            transport.close()
            challenge.fill(0)
            BootstrapProvider.clear()
            onClose(transport)
        }
    }
}
