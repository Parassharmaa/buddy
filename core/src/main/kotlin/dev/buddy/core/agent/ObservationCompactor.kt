package dev.buddy.core.agent

import dev.buddy.core.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Deterministic context reduction: instructions and action/approval results are never summarized.
internal object ObservationCompactor {
    data class Result(val messages: List<ChatMessage>, val count: Int)

    fun compact(messages: List<ChatMessage>): Result {
        val candidates = messages.mapIndexedNotNull { index, message ->
            if (message.role != "tool") return@mapIndexedNotNull null
            val value = runCatching { Json.parseToJsonElement(message.content.orEmpty()).jsonObject }.getOrNull()
                ?: return@mapIndexedNotNull null
            if (value["status"]?.jsonPrimitive?.contentOrNull != "observed" ||
                value["kind"]?.jsonPrimitive?.contentOrNull != "observation" || value["data"] !is JsonObject
            ) {
                return@mapIndexedNotNull null
            }
            index to value
        }.dropLast(2).toMap()
        return Result(
            messages.mapIndexed { index, message ->
                val value = candidates[index] ?: return@mapIndexed message
                message.copy(
                    content = buildJsonObject {
                        put("status", "observed")
                        put("kind", "observation")
                        put("trust", "untrusted")
                        put("source_id", value.getValue("source_id"))
                        put("compacted", true)
                        put(
                            "note",
                            "Older observation retained in the evidence ledger. Observe again for a current target."
                        )
                    }.toString()
                )
            },
            candidates.size
        )
    }
}
