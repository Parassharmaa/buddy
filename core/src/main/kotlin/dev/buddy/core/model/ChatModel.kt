package dev.buddy.core.model

import dev.buddy.core.ChatMessage
import dev.buddy.core.ModelConfig
import dev.buddy.core.ModelReply
import kotlinx.serialization.json.JsonObject

interface ChatModel {
    suspend fun complete(
        config: ModelConfig,
        messages: List<ChatMessage>,
        tools: List<JsonObject> = emptyList()
    ): ModelReply
}
