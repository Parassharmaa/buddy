package dev.buddy.files

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.buddy.storage.WorkspaceArtifacts
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

data class SelectedDocument(val name: String, val mimeType: String, val bytes: ByteArray) {
    override fun toString(): String = "SelectedDocument(redacted)"
}

class DocumentAccess(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    suspend fun read(uri: Uri): SelectedDocument = perform(uri) { signal, descriptor ->
        var name: String? = null
        resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
            signal
        )?.use {
            require(it.moveToFirst())
            name = it.getString(0)
            if (!it.isNull(1)) require(it.getLong(1) in 1..WorkspaceArtifacts.MAX_FILE_BYTES)
        }
        val displayName = requireNotNull(name)
        require(displayName.length in 1..128)
        val mimeType = requireNotNull(resolver.getType(uri)).lowercase()
        val opened = requireNotNull(resolver.openFileDescriptor(uri, "r", signal))
        descriptor.set(opened)
        signal.throwIfCanceled()
        val buffer = ByteArray(16 * 1024)
        val contents = WipingBuffer()
        try {
            ParcelFileDescriptor.AutoCloseInputStream(opened).use { input ->
                while (true) {
                    signal.throwIfCanceled()
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(count <= WorkspaceArtifacts.MAX_FILE_BYTES - contents.size())
                    contents.write(buffer, 0, count)
                }
            }
            SelectedDocument(displayName, mimeType, contents.toByteArray())
        } finally {
            buffer.fill(0)
            contents.wipe()
        }
    }

    suspend fun write(uri: Uri, bytes: ByteArray) {
        require(bytes.size in 1..WorkspaceArtifacts.MAX_FILE_BYTES)
        val contents = bytes.copyOf()
        perform(uri) { signal, descriptor ->
            try {
                val opened = requireNotNull(resolver.openFileDescriptor(uri, "wt", signal))
                descriptor.set(opened)
                signal.throwIfCanceled()
                ParcelFileDescriptor.AutoCloseOutputStream(opened).use { output ->
                    var offset = 0
                    while (offset < contents.size) {
                        signal.throwIfCanceled()
                        val count = minOf(16 * 1024, contents.size - offset)
                        output.write(contents, offset, count)
                        offset += count
                    }
                    output.flush()
                }
            } finally {
                contents.fill(0)
            }
        }
    }

    private suspend fun <T> perform(
        uri: Uri,
        operation: (CancellationSignal, AtomicReference<ParcelFileDescriptor?>) -> T
    ): T = withTimeout(15_000) {
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank())
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            val descriptor = AtomicReference<ParcelFileDescriptor?>()
            val task = AtomicReference<Future<*>?>()
            continuation.invokeOnCancellation {
                signal.cancel()
                runCatching { descriptor.getAndSet(null)?.close() }
                task.get()?.cancel(true)
                executor.purge()
            }
            try {
                task.set(
                    executor.submit {
                        try {
                            signal.throwIfCanceled()
                            val result = operation(signal, descriptor)
                            continuation.resume(result) { _, value, _ ->
                                if (value is SelectedDocument) value.bytes.fill(0)
                            }
                        } catch (_: Exception) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("Document access failed")
                                )
                            }
                        } finally {
                            runCatching { descriptor.getAndSet(null)?.close() }
                        }
                    }
                )
                if (!continuation.isActive) task.get()?.cancel(true)
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Document access unavailable"))
            }
        }
    }

    private class WipingBuffer : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            reset()
        }
    }
    companion object {
        private val executor = ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, "BuddyDocuments").apply { isDaemon = true } }
        )
    }
}
