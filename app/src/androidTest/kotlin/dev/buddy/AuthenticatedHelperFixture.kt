package dev.buddy

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import dev.buddy.adb.BootstrapCommand
import dev.buddy.connection.BootstrapProvider
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import java.io.Closeable
import kotlinx.coroutines.withTimeout

internal class AuthenticatedHelperFixture private constructor(
    val helper: IBuddyHelper,
    private val descriptors: Array<ParcelFileDescriptor>
) : Closeable {
    override fun close() {
        runCatching { helper.disconnect() }
        descriptors.forEach { runCatching { it.close() } }
        BootstrapProvider.clear()
    }
    companion object {
        suspend fun start(context: Context, automation: UiAutomation): AuthenticatedHelperFixture {
            val command = BootstrapCommand.forInstalledApp(context)
            check(command.arguments.none { it.any(Char::isWhitespace) })
            val pending = BootstrapProvider.prepare(ShellProtocol.HANDOFF)
            val descriptors = automation.executeShellCommandRwe(command.arguments.joinToString(" "))
            var connected: IBuddyHelper? = null
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use {
                    it.write((Base64.encodeToString(pending.nonce, Base64.NO_WRAP) + "\n").toByteArray())
                }
                connected = requireNotNull(withTimeout(15_000) { pending.result.await() })
                val caps = connected.capabilities()
                check(caps.getInt("protocol") == ShellProtocol.VERSION)
                check(caps.getInt("helperUid") == ShellProtocol.SHELL_UID)
                return AuthenticatedHelperFixture(connected, descriptors)
            } catch (failure: Exception) {
                val errors = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2])
                val output = errors.readNBytes(minOf(errors.available(), 1024)).toString(Charsets.UTF_8)
                val reason = output.lineSequence().firstOrNull { it.startsWith("BUDDY_BOOTSTRAP_FAILED:") }
                connected?.let { runCatching { it.disconnect() } }
                descriptors.forEach { runCatching { it.close() } }
                BootstrapProvider.clear()
                throw AssertionError(reason ?: "Authenticated fixture helper unavailable", failure)
            }
        }
    }
}
