package dev.buddy.pdf

internal data class PdfExcerpt(val text: String, val pagesRead: Int, val totalPages: Int, val truncated: Boolean) {
    override fun toString(): String = "PdfExcerpt(redacted)"
}
internal object PdfLimits {
    const val INPUT_BYTES = 8 * 1024 * 1024
    const val PAGES = 16
    const val CHARACTERS = 32_768
    const val WORKER_MILLIS = 12_000L
    const val CLIENT_MILLIS = 15_000L
}
