package dev.buddy.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseFilesTest {
    @Test fun utf8LimitCountsEmojiBytesAndKeepsExactText() {
        val text = "😀".repeat(WorkspaceFileTypes.MAX_FILE_BYTES / 4)
        val bytes = ResponseFiles.encode(text)
        try {
            assertEquals(WorkspaceFileTypes.MAX_FILE_BYTES, bytes.size)
            assertEquals(text, bytes.decodeToString(throwOnInvalidSequence = true))
        } finally {
            bytes.fill(0)
        }
        assertThrows(IOException::class.java) { ResponseFiles.encode(text + "a") }
    }

    @Test fun malformedUtf16IsRejectedWithoutEchoingResponseContents() {
        for (text in listOf("private-response-canary\ud800", "private-response-canary\udc00")) {
            val failure = assertThrows(IOException::class.java) { ResponseFiles.encode(text) }
            assertFalse(failure.message.orEmpty().contains("private-response-canary"))
        }
    }

    @Test fun cancellationIsCheckedDuringLargeResponsesWithSurrogatePairs() {
        var polls = 0
        assertThrows(CancellationException::class.java) {
            ResponseFiles.encode("a" + "😀".repeat(65_536)) { ++polls < 4 }
        }
    }
}
