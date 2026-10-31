package dev.buddy.core

import java.io.IOException
import kotlinx.coroutines.CancellationException

object ResponseFiles {
    fun encode(text: String, admitted: () -> Boolean = { true }): ByteArray {
        fun checkActive() {
            if (!admitted()) throw CancellationException("Response file cancelled")
        }
        checkActive()
        require(text.isNotEmpty())
        var bytes = 0
        var index = 0
        var nextCheck = 8192
        while (index < text.length) {
            if (index >= nextCheck) {
                checkActive()
                nextCheck = index + 8192
            }
            val character = text[index++]
            bytes += when {
                character.code < 0x80 -> 1

                character.code < 0x800 -> 2

                character.isHighSurrogate() -> {
                    if (index >= text.length || !text[index++].isLowSurrogate()) {
                        throw IOException("Response contains invalid text")
                    }
                    4
                }

                character.isLowSurrogate() -> throw IOException("Response contains invalid text")

                else -> 3
            }
            if (bytes > WorkspaceFileTypes.MAX_FILE_BYTES) throw IOException("Response exceeds 8 MiB")
        }
        checkActive()
        val result = text.encodeToByteArray(throwOnInvalidSequence = true)
        try {
            checkActive()
            return result
        } catch (failure: Throwable) {
            result.fill(0)
            throw failure
        }
    }
}
