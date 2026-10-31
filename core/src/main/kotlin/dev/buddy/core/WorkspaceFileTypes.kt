package dev.buddy.core

object WorkspaceFileTypes {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private val types = mapOf(
        "text/plain" to setOf("txt"),
        "text/markdown" to setOf("md", "markdown"),
        "application/json" to setOf("json"),
        "application/pdf" to setOf("pdf"),
        "image/png" to setOf("png"),
        "image/jpeg" to setOf("jpg", "jpeg"),
        "image/webp" to setOf("webp"),
        "image/avif" to setOf("avif"),
        "image/heic" to setOf("heic", "heif"),
        "image/heif" to setOf("heic", "heif")
    )
    fun extensions(mimeType: String): Set<String>? = types[mimeType]
}
