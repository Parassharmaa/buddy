package dev.buddy.core

import java.net.URI

class ModelEndpoint private constructor(val uri: URI) {
    val chatUrl: String get() = uri.toString().trimEnd('/') + "/chat/completions"

    companion object {
        fun parse(value: String, allowLocalHttp: Boolean = false): ModelEndpoint {
            val uri = URI(value.trim())
            require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
                "Use an HTTPS API base URL"
            }
            require(uri.userInfo == null && uri.fragment == null && uri.query == null) {
                "Remove credentials, query and fragment from the URL"
            }
            require(uri.port == -1 || uri.port in 1..65535)
            val host = uri.host.lowercase().removePrefix("[").removeSuffix("]")
            val numbers = host.split('.').mapNotNull {
                it.toIntOrNull()?.takeIf { n -> n in 0..255 }
            }
            val local = host in setOf("localhost", "::1") ||
                (
                    numbers.size == 4 && (
                        numbers[0] == 127 || numbers[0] == 10 ||
                            (numbers[0] == 192 && numbers[1] == 168) ||
                            (numbers[0] == 172 && numbers[1] in 16..31)
                        )
                    )
            require(uri.scheme == "https" || (allowLocalHttp && local)) {
                "HTTP requires an explicitly enabled local-network endpoint"
            }
            require(!uri.path.contains("..") && !uri.path.endsWith("/chat/completions")) {
                "Use the API base URL, such as https://api.openai.com/v1"
            }
            return ModelEndpoint(uri)
        }
    }
}
