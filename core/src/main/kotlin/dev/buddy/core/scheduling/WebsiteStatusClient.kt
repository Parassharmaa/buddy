package dev.buddy.core.scheduling

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A status check reads only response headers and never forwards website sessions or model credentials. */
class WebsiteStatusClient : AutoCloseable {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun check(address: StatusAddress, admitted: () -> Boolean): Int =
        suspendCancellableCoroutine { continuation ->
            if (!admitted()) {
                continuation.cancel()
                return@suspendCancellableCoroutine
            }
            val call = http.newCall(Request.Builder().url(address.url).head().build())
            continuation.invokeOnCancellation { call.cancel() }
            if (!admitted()) {
                continuation.cancel()
                return@suspendCancellableCoroutine
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IOException("Website status unavailable")
                        )
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val code = response.use { it.code.also { code -> require(code in 100..599) } }
                        if (continuation.isActive) continuation.resume(code)
                    } catch (_: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("Website status unavailable")
                            )
                        }
                    }
                }
            })
        }
    override fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}
