package dev.buddy.files

import android.os.Handler
import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import dev.buddy.storage.WorkspaceArtifacts
import java.io.FileNotFoundException

internal object PrivateFileDescriptors {
    private val lock = Any()
    private val handles = mutableSetOf<Handle>()
    private var total = 0
    private var generation = 0L
    val handler by lazy { Handler(HandlerThread("buddy-private-file-reads").apply { start() }.looper) }
    fun ticket(): Long = synchronized(lock) { generation }
    fun reserve(size: Int, ticket: Long): Handle = synchronized(lock) {
        if (ticket != generation || size !in 1..WorkspaceArtifacts.MAX_FILE_BYTES ||
            handles.size >= 16 || size > WorkspaceArtifacts.MAX_WORKSPACE_BYTES - total
        ) {
            throw FileNotFoundException("Private file reader limit reached")
        }
        Handle(size).also {
            handles.add(it)
            total += size
        }
    }
    fun revokeAll() = synchronized(lock) {
        generation++
        handles.toList().forEach { it.onRelease() }
    }
    class Handle internal constructor(private val size: Int) : ProxyFileDescriptorCallback() {
        private var contents: ByteArray? = null
        fun assign(bytes: ByteArray): Boolean = synchronized(lock) {
            if (this !in handles || bytes.size != size) {
                bytes.fill(0)
                false
            } else {
                contents = bytes
                true
            }
        }
        override fun onGetSize(): Long = synchronized(lock) {
            if (this !in handles) throw ErrnoException("Private file closed", OsConstants.EBADF)
            size.toLong()
        }
        override fun onRead(offset: Long, requested: Int, data: ByteArray): Int = synchronized(lock) {
            val bytes = contents ?: throw ErrnoException("Private file closed", OsConstants.EBADF)
            if (offset < 0 || requested < 0 || requested > data.size) {
                throw ErrnoException("Invalid private file range", OsConstants.EINVAL)
            }
            if (offset >= bytes.size) return@synchronized 0
            val count = minOf(requested.toLong(), bytes.size - offset).toInt()
            bytes.copyInto(data, 0, offset.toInt(), offset.toInt() + count)
            count
        }
        override fun onRelease() = synchronized(lock) {
            if (handles.remove(this)) {
                total -= size
                contents?.fill(0)
                contents = null
            }
        }
    }
}
