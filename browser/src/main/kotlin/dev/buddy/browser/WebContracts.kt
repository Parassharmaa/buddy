package dev.buddy.browser

import kotlinx.serialization.Serializable

@Serializable
enum class WebStatus { EMPTY, LOADING, READY, PAUSED, BLOCKED, FAILED, RENDERER_GONE }

@Serializable
enum class WebReadStatus { ALLOWED, PRIVATE, UNSUPPORTED, STALE }

@Serializable
data class WebSnapshot(
    val origin: String,
    val sourceLabel: String,
    val title: String,
    val text: String,
    val truncated: Boolean = false
) {
    override fun toString(): String = "WebSnapshot(redacted)"
}

sealed interface WebReadResult {
    data class Allowed(val snapshot: WebSnapshot) : WebReadResult
    data class Suppressed(val status: WebReadStatus) : WebReadResult
}

data class WebState(
    val status: WebStatus = WebStatus.EMPTY,
    val address: String = "",
    val title: String = "",
    val pendingOrigin: String? = null,
    val canRead: Boolean = false,
    val canMute: Boolean = false,
    val soundAllowed: Boolean = false,
    val hasUploadRequest: Boolean = false
) {
    override fun toString(): String = "WebState(status=$status)"
}

interface WebPort {
    val state: kotlinx.coroutines.flow.StateFlow<WebState>
    suspend fun observe(expectedOrigin: String): WebReadResult
}
