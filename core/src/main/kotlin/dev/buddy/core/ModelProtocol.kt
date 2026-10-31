package dev.buddy.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ModelConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val localHttp: Boolean = false,
    val toolCallingVerified: Boolean = false
) {
    override fun toString(): String = "ModelConfig(redacted)"
}

@Serializable
data class FunctionCall(val name: String, val arguments: String)

@Serializable
data class ToolCall(val id: String, val type: String = "function", val function: FunctionCall)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sources: List<ChatSource> = emptyList()
) {
    override fun toString(): String = "ChatMessage(role=$role, redacted)"
}

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val runs: List<RunRecord> = emptyList()
)

data class ModelReply(val message: ChatMessage, val tokens: Int?, val finishReason: String)
class ModelFailure(val category: String, override val message: String) : Exception(message)

// Reassembles interleaved tool-call fragments without guessing or executing partial JSON.
class StreamingReply {
    private val text = StringBuilder()
    private data class Call(var id: String = "", var name: String = "", var arguments: String = "")
    private val calls = sortedMapOf<Int, Call>()
    private var reason: String? = null
    private var tokens: Int? = null
    private val json = modelJson
    fun accept(payload: String) {
        val chunk = json.parseToJsonElement(payload).jsonObject
        (chunk["usage"] as? JsonObject)?.get("total_tokens")?.takeUnless { it == JsonNull }?.let { value ->
            val count = value.jsonPrimitive.intOrNull
            require(!value.jsonPrimitive.isString && count != null && count >= 0) { "Invalid token usage" }
            require(tokens == null || count >= requireNotNull(tokens)) { "Token usage decreased" }
            tokens = count
        }
        val choices = chunk["choices"]?.jsonArray ?: return
        if (choices.isEmpty()) return
        require(choices.size == 1) { "Only one completion is supported" }
        val choice = choices.single().jsonObject
        choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { reason = it }
        val delta = (choice["delta"] ?: choice["message"])?.jsonObject ?: return
        delta["role"]?.jsonPrimitive?.contentOrNull?.let { require(it == "assistant") { "Invalid completion role" } }
        delta["content"]?.jsonPrimitive?.contentOrNull?.let { text.append(it) }
        delta["tool_calls"]?.jsonArray?.forEach { entry ->
            val item = entry.jsonObject
            item["type"]?.let {
                require(it.jsonPrimitive.contentOrNull == "function") { "Unsupported tool-call type" }
            }
            val index = item["index"]?.jsonPrimitive?.intOrNull ?: calls.size
            require(index in 0..15)
            val call = calls.getOrPut(index) { Call() }
            item["id"]?.jsonPrimitive?.contentOrNull?.let { call.id += it }
            item["function"]?.jsonObject?.let { function ->
                function["name"]?.jsonPrimitive?.contentOrNull?.let { call.name += it }
                function["arguments"]?.jsonPrimitive?.contentOrNull?.let { call.arguments += it }
            }
        }
        require(text.length + calls.values.sumOf { it.arguments.length + it.name.length + it.id.length } <= 131_072)
    }
    fun finish(): ModelReply {
        val finish =
            reason ?: throw ModelFailure(
                "truncated",
                "The provider response ended early. No action from this response was executed."
            )
        if (finish !in
            setOf("stop", "tool_calls")
        ) {
            throw ModelFailure("truncated", "The provider did not return a complete answer.")
        }
        val toolCalls = calls.values.map { call ->
            require(call.id.length in 1..128 && call.name.matches(Regex("[A-Za-z0-9_]{1,64}")))
            require(json.parseToJsonElement(call.arguments) is JsonObject)
            ToolCall(call.id, function = FunctionCall(call.name, call.arguments))
        }
        require(toolCalls.map { it.id }.distinct().size == toolCalls.size)
        require((finish == "tool_calls") == toolCalls.isNotEmpty())
        if (text.isBlank() && toolCalls.isEmpty()) {
            throw ModelFailure("protocol", "The model returned no answer or tool call.")
        }
        return ModelReply(
            ChatMessage(
                "assistant",
                text.toString().ifEmpty {
                    null
                },
                toolCalls.ifEmpty { null }
            ),
            tokens,
            finish
        )
    }
}
