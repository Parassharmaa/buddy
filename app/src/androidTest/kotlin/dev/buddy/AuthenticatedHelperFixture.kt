package dev.buddy

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
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
        suspend fun start(
            context: Context,
            automation: UiAutomation,
            viaShell: Boolean = false
        ): AuthenticatedHelperFixture {
            val command = BootstrapCommand.forInstalledApp(context)
            check(command.arguments.none { it.any(Char::isWhitespace) })
            val pending = BootstrapProvider.prepare(ShellProtocol.HANDOFF)
            val descriptors = automation.executeShellCommandRwe(
                if (viaShell) "/system/bin/sh" else command.arguments.joinToString(" ")
            )
            var connected: IBuddyHelper? = null
            try {
                if (viaShell) {
                    // Send the nonce only after the shell has parsed the complete exec line,
                    // so shell input buffering cannot consume the helper's secret stdin.
                    val marker = "BUDDY_SHELL_READY\n".toByteArray()
                    ParcelFileDescriptor.AutoCloseOutputStream(
                        ParcelFileDescriptor.dup(descriptors[1].fileDescriptor)
                    ).use {
                        it.write(("printf 'BUDDY_SHELL_READY\\n'; " + command.value + "\n").toByteArray())
                        it.flush()
                    }
                    val poll = StructPollfd().apply {
                        fd = descriptors[0].fileDescriptor
                        events = OsConstants.POLLIN.toShort()
                    }
                    check(Os.poll(arrayOf(poll), 5_000) > 0) { "Bootstrap shell unavailable" }
                    ParcelFileDescriptor.AutoCloseInputStream(
                        ParcelFileDescriptor.dup(descriptors[0].fileDescriptor)
                    ).use {
                        check(it.readNBytes(marker.size).contentEquals(marker)) { "Bootstrap shell failed" }
                    }
                }
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
