package dev.buddy.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import dev.buddy.storage.ArtifactReadBudget
import dev.buddy.storage.EncryptedStore
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class PdfSourceReader(context: Context, private val store: EncryptedStore) {
    private val application = context.applicationContext
    suspend fun read(workspace: String, id: String): PdfExcerpt {
        if (Build.VERSION.SDK_INT < 35) error("PDF text requires Android 15")
        check(operations.tryLock()) { "Another PDF preview is active" }
        try {
            return withContext(Dispatchers.IO) {
                val bytes = store.artifacts.read(workspace, id, ArtifactReadBudget(PdfLimits.INPUT_BYTES))
                val descriptor = try {
                    require(bytes.size in 1..PdfLimits.INPUT_BYTES)
                    readOnlyMemory(bytes)
                } finally {
                    bytes.fill(0)
                }
                descriptor.use { extract(it) }
            }
        } finally {
            operations.unlock()
        }
    }
    private fun readOnlyMemory(bytes: ByteArray): ParcelFileDescriptor {
        val descriptor = Os.memfd_create("buddy-pdf-preview", OsConstants.MFD_CLOEXEC)
        try {
            Os.fchmod(descriptor, OsConstants.S_IRUSR)
            var written = 0
            while (written < bytes.size) {
                val count = Os.write(descriptor, bytes, written, bytes.size - written)
                check(count > 0)
                written += count
            }
            Os.lseek(descriptor, 0, OsConstants.SEEK_SET)
            return ParcelFileDescriptor.dup(descriptor).use { owned ->
                // Reopen our own descriptor read-only; the isolated worker receives no writable handle.
                ParcelFileDescriptor.open(File("/proc/self/fd/${owned.fd}"), ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } finally {
            Os.close(descriptor)
        }
    }

    @RequiresApi(35)
    private suspend fun extract(input: ParcelFileDescriptor): PdfExcerpt = withContext(Dispatchers.Main.immediate) {
        val request = UUID.randomUUID().toString()
        var bound = false
        val remote = AtomicReference<IPdfReader?>()
        var connection: ServiceConnection? = null
        try {
            withTimeout(PdfLimits.CLIENT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val settled = AtomicBoolean()
                    fun fail(stage: String = "DISCONNECTED") {
                        if (settled.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("PDF preview unavailable ($stage)")
                            )
                        }
                    }
                    val reply = object : IPdfReply.Stub() {
                        override fun completed(
                            requestId: String?,
                            text: String?,
                            pagesRead: Int,
                            totalPages: Int,
                            truncated: Boolean,
                            error: Int
                        ) {
                            if (!continuation.isActive) return
                            if (!Process.isIsolatedUid(Binder.getCallingUid()) || requestId != request) {
                                fail("INVALID_REPLY")
                                return
                            }
                            if (error != 0) {
                                fail(
                                    when (error) {
                                        1 -> "UNSUPPORTED"
                                        2 -> "ISOLATION"
                                        3 -> "BUSY"
                                        4 -> "INPUT"
                                        5 -> "PARSER"
                                        else -> "INVALID_REPLY"
                                    }
                                )
                                return
                            }
                            val result = runCatching {
                                require(Process.isIsolatedUid(Binder.getCallingUid()))
                                require(requestId == request && error == 0)
                                require(text != null && text.length <= PdfLimits.CHARACTERS && text.isNotBlank())
                                require(pagesRead in 1..PdfLimits.PAGES && totalPages >= pagesRead)
                                require(text.lastOrNull()?.isHighSurrogate() != true)
                                PdfExcerpt(text, pagesRead, totalPages, truncated || pagesRead < totalPages)
                            }
                            result.onSuccess {
                                if (settled.compareAndSet(false, true) && continuation.isActive) continuation.resume(it)
                            }
                                .onFailure { fail("INVALID_REPLY") }
                        }
                    }
                    val selected = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            if (!continuation.isActive) return
                            runCatching {
                                val reader = IPdfReader.Stub.asInterface(service)
                                remote.set(reader)
                                reader.extract(request, input, reply)
                            }.onFailure { fail() }
                        }
                        override fun onServiceDisconnected(name: ComponentName) = fail()
                        override fun onBindingDied(name: ComponentName) = fail()
                        override fun onNullBinding(name: ComponentName) = fail()
                    }
                    connection = selected
                    bound = application.bindIsolatedService(
                        Intent(application, IsolatedPdfService::class.java),
                        Context.BindServiceFlags.of(Context.BIND_AUTO_CREATE.toLong()),
                        request,
                        application.mainExecutor,
                        selected
                    )
                    if (!bound) fail()
                    continuation.invokeOnCancellation {
                        settled.set(true)
                        runCatching { remote.get()?.cancel(request) }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                runCatching { remote.get()?.cancel(request) }
                if (bound) connection?.let { runCatching { application.unbindService(it) } }
            }
        }
    }
    companion object {
        private val operations = Mutex()
    }
}
