package dev.buddy.core.model

import dev.buddy.core.ChatMessage
import dev.buddy.core.ModelConfig
import dev.buddy.core.ModelEndpoint
import dev.buddy.core.ModelFailure
import dev.buddy.core.ModelReply
import dev.buddy.core.StreamingReply
import dev.buddy.core.modelJson
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class ModelClient :
    ChatModel,
    java.io.Closeable {
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val json = modelJson

    override suspend fun complete(
        config: ModelConfig,
        messages: List<ChatMessage>,
        tools: List<JsonObject>
    ): ModelReply {
        require(config.model.isNotBlank() && config.model.length <= 200)
        val endpoint = ModelEndpoint.parse(config.baseUrl, config.localHttp)
        val body = buildJsonObject {
            put("model", config.model)
            put("messages", json.encodeToJsonElement(ListSerializer(ChatMessage.serializer()), messages))
            put("stream", true)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            if (tools.isNotEmpty()) put("tools", JsonArray(tools))
        }.toString().toByteArray()
        require(body.size <= 2 * 1024 * 1024)
        val request = Request.Builder().url(endpoint.chatUrl)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Accept", "text/event-stream, application/json")
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer " + config.apiKey) }
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(safeFailure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val reply = response.use { parse(it) }
                        if (continuation.isActive) continuation.resume(reply)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(safeFailure(failure))
                    }
                }
            })
        }
    }

    private fun safeFailure(failure: Exception): ModelFailure = when (failure) {
        is ModelFailure -> failure

        is IOException -> ModelFailure("connection", "The model connection failed. Check the endpoint and network.")

        else -> ModelFailure(
            "protocol",
            "The provider returned invalid response data. No action from this response was executed."
        )
    }

    private fun parse(response: Response): ModelReply {
        when (response.code) {
            in 200..299 -> Unit
            401, 403 -> throw ModelFailure("authentication", "The provider rejected the API key or model access.")
            429 -> throw ModelFailure("rate_limit", "The provider is rate limiting requests. Try again later.")
            in 300..399 -> throw ModelFailure("endpoint", "The endpoint redirected. Enter the final API base URL.")
            400, 404, 422 -> throw ModelFailure("protocol", "Check the API base URL, model and tool/streaming support.")
            else -> throw ModelFailure("provider", "The provider could not complete this request.")
        }
        val parser = StreamingReply()
        val bounded = object : FilterInputStream(response.body.byteStream()) {
            private var remaining = 4 * 1024 * 1024
            override fun read(): Int {
                check(remaining > 0) { "Response limit" }
                return super.read().also { if (it >= 0) remaining-- }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                check(remaining > 0) { "Response limit" }
                return `in`.read(buffer, offset, minOf(length, remaining)).also { if (it > 0) remaining -= it }
            }
        }
        bounded.bufferedReader().use { reader ->
            if (response.header("Content-Type")?.startsWith("text/event-stream", ignoreCase = true) == true) {
                var data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty() && data.isNotEmpty()) {
                        val payload = data.toString()
                        if (payload == "[DONE]") break
                        parser.accept(payload)
                        data = StringBuilder()
                    } else if (line.startsWith("data:")) {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trimStart())
                    }
                }
            } else {
                parser.accept(reader.readText())
            }
        }
        return parser.finish()
    }

    override fun close() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    suspend fun probe(config: ModelConfig): String {
        val schema = json.parseToJsonElement(
            """{"type":"function","function":{"name":"buddy_probe","description":"Return this harmless setup token","parameters":{"type":"object","properties":{"echo":{"type":"string","enum":["ready"]}},"required":["echo"],"additionalProperties":false}}}"""
        ) as JsonObject
        val prompt =
            listOf(
                ChatMessage("user", "Call buddy_probe once with echo ready. This is a harmless compatibility check.")
            )
        val reply = complete(config, prompt, listOf(schema))
        val call =
            reply.message.toolCalls?.singleOrNull()
                ?: throw ModelFailure("tools", "This model did not return the required tool call.")
        if (call.function.name != "buddy_probe" ||
            json.parseToJsonElement(call.function.arguments) != json.parseToJsonElement("""{"echo":"ready"}""")
        ) {
            throw ModelFailure("tools", "The model returned an invalid test tool call.")
        }
        val followup = complete(
            config,
            prompt + reply.message + ChatMessage("tool", "{\"ok\":true}", toolCallId = call.id)
        )
        if (!followup.message.toolCalls.isNullOrEmpty() || followup.message.content.isNullOrBlank()) {
            throw ModelFailure("tools", "The model did not acknowledge the test tool result.")
        }
        return "Tool calling and response verified"
    }
}
