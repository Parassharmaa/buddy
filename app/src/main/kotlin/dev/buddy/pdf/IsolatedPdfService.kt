package dev.buddy.pdf

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.pdf.PdfRenderer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class IsolatedPdfService : Service() {
    private val active = AtomicReference<String?>()
    private val worker = Executors.newSingleThreadExecutor()
    private val deadline = Executors.newSingleThreadScheduledExecutor()
    private fun finishProcess() {
        if (Process.isIsolated()) Process.killProcess(Process.myPid())
    }
    private fun trustedCaller() = Binder.getCallingUid() == applicationInfo.uid && Process.isIsolated()
    private val binder = object : IPdfReader.Stub() {
        override fun extract(requestId: String?, input: ParcelFileDescriptor?, reply: IPdfReply?) {
            if (!trustedCaller() || requestId == null || input == null || reply == null ||
                runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false).not()
            ) {
                input?.close()
                return
            }
            if (!active.compareAndSet(null, requestId)) {
                input.close()
                reply.completed(requestId, "", 0, 0, false, 3)
                return
            }
            deadline.schedule(::finishProcess, PdfLimits.WORKER_MILLIS, TimeUnit.MILLISECONDS)
            worker.execute {
                var stage = 2
                try {
                    check(checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED)
                    check(!File(applicationInfo.dataDir).canRead())
                    stage = 4
                    check(Os.fstat(input.fileDescriptor).st_size in 1..PdfLimits.INPUT_BYTES.toLong())
                    check(
                        Os.fcntlInt(input.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE ==
                            OsConstants.O_RDONLY
                    )
                    if (Build.VERSION.SDK_INT < 35) {
                        reply.completed(requestId, "", 0, 0, false, 1)
                    } else {
                        stage = 5
                        val result = read(input)
                        reply.completed(
                            requestId,
                            result.text,
                            result.pagesRead,
                            result.totalPages,
                            result.truncated,
                            0
                        )
                    }
                } catch (_: Exception) {
                    runCatching { reply.completed(requestId, "", 0, 0, false, stage) }
                } finally {
                    runCatching { input.close() }
                    finishProcess()
                }
            }
        }
        override fun cancel(requestId: String?) {
            if (trustedCaller() && requestId != null && active.get() == requestId) finishProcess()
        }
    }

    @RequiresApi(35)
    private fun read(input: ParcelFileDescriptor): PdfExcerpt = PdfRenderer(input).use { renderer ->
        require(renderer.pageCount > 0)
        val text = StringBuilder()
        var pages = 0
        var truncated = renderer.pageCount > PdfLimits.PAGES
        for (index in 0 until minOf(renderer.pageCount, PdfLimits.PAGES)) {
            if (text.length >= PdfLimits.CHARACTERS) {
                truncated = true
                break
            }
            renderer.openPage(index).use { page ->
                for (block in page.textContents) {
                    val remaining = PdfLimits.CHARACTERS - text.length
                    if (remaining <= 0) {
                        truncated = true
                        break
                    }
                    if (text.isNotEmpty()) text.append('\n')
                    val limit = PdfLimits.CHARACTERS - text.length
                    if (block.text.length > limit) truncated = true
                    text.append(block.text.take(limit))
                }
            }
            pages++
        }
        if (text.lastOrNull()?.isHighSurrogate() == true) text.setLength(text.length - 1)
        PdfExcerpt(text.toString(), pages, renderer.pageCount, truncated)
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        worker.shutdownNow()
        deadline.shutdownNow()
        super.onDestroy()
        finishProcess()
    }
}
