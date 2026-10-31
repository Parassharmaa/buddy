package dev.buddy.browser

import dev.buddy.core.WorkspaceFileTypes
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

data class WebDownloadRequest(
    val id: String,
    val address: WebAddress,
    val nameHint: String,
    val mimeHint: String,
    val claimedBytes: Long
) {
    override fun toString(): String = "WebDownloadRequest(redacted)"
}

class WebDownloadGrant internal constructor(
    internal val address: WebAddress,
    internal val cookie: String?,
    private val issuedAt: Long = System.nanoTime(),
    private val now: () -> Long = System::nanoTime
) {
    private val claimed = AtomicBoolean()
    internal fun claim() {
        check(claimed.compareAndSet(false, true) && now() - issuedAt in 0..5_000_000_000L)
    }
    override fun toString(): String = "WebDownloadGrant(redacted)"
}

class DownloadedFile(val name: String, val mimeType: String, val origin: String, val bytes: ByteArray) {
    override fun toString(): String = "DownloadedFile(redacted)"
}

class BrowserDownloader : AutoCloseable {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private sealed interface Step {
        data class Redirect(val address: WebAddress) : Step
        data class Complete(val file: DownloadedFile) : Step
    }

    suspend fun fetch(grant: WebDownloadGrant): DownloadedFile = withTimeout(30_000) {
        grant.claim()
        require(grant.cookie == null || grant.cookie.length <= 16_384)
        var address = grant.address
        repeat(4) { index ->
            check(address.origin == grant.address.origin)
            val request = Request.Builder().url(address.url).get()
                .apply { grant.cookie?.takeIf { it.isNotEmpty() }?.let { header("Cookie", it) } }.build()
            when (val step = step(request, address)) {
                is Step.Complete -> return@withTimeout step.file

                is Step.Redirect -> {
                    check(index < 3 && step.address.origin == grant.address.origin)
                    address = step.address
                }
            }
        }
        error("Download redirect limit")
    }

    private suspend fun step(request: Request, address: WebAddress): Step =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("Download stopped")))
                }
                override fun onResponse(call: Call, response: Response) {
                    var decoded: Step? = null
                    try {
                        response.use { decoded = decode(call, it, address) }
                        continuation.resume(requireNotNull(decoded), onCancellation = { _, result, _ ->
                            if (result is Step.Complete) result.file.bytes.fill(0)
                        })
                    } catch (_: Exception) {
                        (decoded as? Step.Complete)?.file?.bytes?.fill(0)
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.failure(IOException("Download rejected"))
                            )
                        }
                    }
                }
            })
        }

    private fun decode(call: Call, response: Response, address: WebAddress): Step {
        if (response.code in setOf(301, 302, 303, 307, 308)) {
            val location = requireNotNull(response.header("Location")).also { require(it.length <= 4096) }
            val next = requireNotNull(address.url.toHttpUrl().resolve(location))
            return Step.Redirect(WebAddress.parse(next.toString()))
        }
        require(response.code in 200..299)
        val mime = response.body.contentType()?.let { "${it.type}/${it.subtype}" }?.lowercase()
            ?: error("Missing file type")
        val extensions = requireNotNull(WorkspaceFileTypes.extensions(mime))
        val filename = filename(response.header("Content-Disposition"), address, extensions)
        val length = response.body.contentLength()
        require(length == -1L || length in 1..WorkspaceFileTypes.MAX_FILE_BYTES)
        val buffer = ByteArray(WorkspaceFileTypes.MAX_FILE_BYTES + 1)
        try {
            var received = 0
            val input = response.body.byteStream()
            while (true) {
                check(!call.isCanceled())
                val count = input.read(buffer, received, buffer.size - received)
                if (count == -1) break
                check(count > 0)
                received += count
                require(received <= WorkspaceFileTypes.MAX_FILE_BYTES)
            }
            require(received > 0)
            return Step.Complete(DownloadedFile(filename, mime, address.origin, buffer.copyOf(received)))
        } finally {
            buffer.fill(0)
        }
    }

    private fun filename(disposition: String?, address: WebAddress, extensions: Set<String>): String {
        require(disposition == null || disposition.length <= 4096)
        val encoded = disposition?.let {
            Regex("(?:^|;)\\s*filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        val regular = disposition?.let {
            Regex("(?:^|;)\\s*filename=\"?([^\";]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        val candidate = encoded?.let { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
            ?: regular ?: address.url.toHttpUrl().pathSegments.lastOrNull().orEmpty()
        val leaf = candidate.substringAfterLast('/').substringAfterLast('\\').trim()
        require(leaf.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' })
        val name = if (leaf.isBlank() || '.' !in leaf) "download.${extensions.first()}" else leaf
        require(name.length in 1..128 && name.substringAfterLast('.').lowercase() in extensions)
        return name
    }

    override fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}
