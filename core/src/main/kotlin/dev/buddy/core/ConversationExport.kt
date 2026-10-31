package dev.buddy.core

import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream

@Serializable
data class ConversationExport(val schema: Int, val conversation: Conversation) {
    init {
        require(schema == 1)
    }
    override fun toString(): String = "ConversationExport(redacted)"
}

object ConversationExports {
    const val MAX_BYTES = 8 * 1024 * 1024

    @OptIn(ExperimentalSerializationApi::class)
    fun encode(conversation: Conversation, admitted: () -> Boolean = { true }): ByteArray =
        Buffer(admitted).use { output ->
            Json.encodeToStream(
                ConversationExport.serializer(),
                ConversationExport(schema = 1, conversation = conversation),
                output
            )
            output.bytes()
        }

    private class Buffer(private val admitted: () -> Boolean) : OutputStream() {
        private val chunks = mutableListOf<ByteArray>()
        private var size = 0
        private fun allow(length: Int) {
            if (!admitted()) throw CancellationException("Conversation export cancelled")
            if (length > MAX_BYTES - size) throw IOException("Conversation export exceeds 8 MiB")
        }
        override fun write(value: Int) {
            allow(1)
            if (size % CHUNK == 0) chunks.add(ByteArray(CHUNK))
            chunks[size / CHUNK][size % CHUNK] = value.toByte()
            size++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            allow(length)
            var copied = 0
            while (copied < length) {
                if (size % CHUNK == 0) chunks.add(ByteArray(CHUNK))
                val count = minOf(length - copied, CHUNK - size % CHUNK)
                bytes.copyInto(chunks[size / CHUNK], size % CHUNK, offset + copied, offset + copied + count)
                copied += count
                size += count
            }
        }
        fun bytes(): ByteArray {
            allow(0)
            return ByteArray(size).also { result ->
                chunks.forEachIndexed { index, chunk ->
                    chunk.copyInto(result, index * CHUNK, 0, minOf(CHUNK, size - index * CHUNK))
                }
            }
        }
        override fun close() {
            chunks.forEach { it.fill(0) }
            chunks.clear()
            size = 0
        }
        companion object {
            private const val CHUNK = 8192
        }
    }
}
