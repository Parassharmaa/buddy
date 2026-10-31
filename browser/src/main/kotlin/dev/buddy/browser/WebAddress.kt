package dev.buddy.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class WebAddress private constructor(val url: String, val origin: String, val sourceLabel: String) {
    companion object {
        fun parse(value: String): WebAddress {
            require(value.length in 1..4096 && value == value.trim())
            require(
                value.none {
                    it.isISOControl() || it == '\\' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
                }
            )
            require(value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true))
            val authority = value.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
            require('@' !in authority)
            val parsed = requireNotNull(value.toHttpUrlOrNull())
            require(parsed.scheme in setOf("http", "https") && parsed.username.isEmpty() && parsed.password.isEmpty())
            val canonical = parsed.newBuilder().host(parsed.host.removeSuffix(".")).build()
            val origin = canonical.newBuilder().encodedPath(
                "/"
            ).query(null).fragment(null).build().toString().removeSuffix("/")
            val label = canonical.newBuilder().query(null).fragment(null).build().toString()
            return WebAddress(canonical.toString(), origin, label)
        }
    }
}
