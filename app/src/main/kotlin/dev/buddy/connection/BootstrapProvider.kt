package dev.buddy.connection

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import dev.buddy.shell.IBuddyHelper
import dev.buddy.shell.ShellProtocol
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred

class BootstrapProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == ShellProtocol.SHELL_UID) { "Shell bootstrap only" }
        require(arg == null && method in setOf(ShellProtocol.HANDOFF, ShellProtocol.REVALIDATE))
        val data = requireNotNull(extras)
        require(data.size() <= 3 && data.getInt("protocol") == ShellProtocol.VERSION)
        val nonce = requireNotNull(data.getByteArray("nonce"))
        require(nonce.size == 32)
        synchronized(lock) {
            val expected = requireNotNull(pending)
            check(expected.method == method && SystemClock.elapsedRealtime() < expected.deadline)
            check(MessageDigest.isEqual(nonce, expected.nonce))
            pending = null
            expected.nonce.fill(0)
            if (method == ShellProtocol.HANDOFF) {
                val binder = requireNotNull(data.getBinder("helper"))
                check(binder.isBinderAlive)
                val helper = IBuddyHelper.Stub.asInterface(binder)
                expected.deliveredHelper = helper
                // Probe on the connection worker after returning; never reenter helper on this Binder thread.
                expected.result.complete(helper)
            } else {
                expected.result.complete(null)
            }
        }
        return Bundle().apply { putBoolean("accepted", true) }
    }
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = error("Unsupported")
    override fun getType(uri: Uri): String? = error("Unsupported")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unsupported")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unsupported")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Unsupported")

    data class Pending(
        val method: String,
        val nonce: ByteArray,
        val deadline: Long,
        val result: CompletableDeferred<IBuddyHelper?>
    ) {
        @Volatile var deliveredHelper: IBuddyHelper? = null
            internal set
        override fun toString(): String = "Pending(redacted)"
    }
    companion object {
        private val lock = Any()
        private var pending: Pending? = null
        fun prepare(method: String): Pending = synchronized(lock) {
            require(method in setOf(ShellProtocol.HANDOFF, ShellProtocol.REVALIDATE))
            pending?.nonce?.fill(0)
            pending?.result?.cancel()
            Pending(
                method,
                ByteArray(32).also(SecureRandom()::nextBytes),
                SystemClock.elapsedRealtime() + 15_000,
                CompletableDeferred()
            ).also {
                pending =
                    it
            }
        }
        fun clear() = synchronized(lock) {
            pending?.nonce?.fill(0)
            pending?.result?.cancel()
            pending = null
        }
    }
}
