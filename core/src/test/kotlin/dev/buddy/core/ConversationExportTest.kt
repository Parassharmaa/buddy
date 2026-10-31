package dev.buddy.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExportTest {
    @Test fun archivesRetainReviewedSourceTextAndUseABoundOnUtf8Bytes() {
        val config = ModelConfig("https://example.com/v1", "fixture", "")
        val source =
            ChatSource(
                "source",
                ChatSourceKind.FILE,
                "reference",
                "Quoted reference",
                false,
                SourceContext.destination(config)
            )
        val conversation =
            Conversation("conversation", "Fixture", listOf(ChatMessage("user", "Hello café", sources = listOf(source))))
        val bytes = ConversationExports.encode(conversation)
        try {
            val decoded = Json.decodeFromString<ConversationExport>(bytes.decodeToString())
            assertEquals(conversation, decoded.conversation)
            assertEquals(1, decoded.schema)
            assertTrue(bytes.size < ConversationExports.MAX_BYTES)
        } finally {
            bytes.fill(0)
        }
        val huge = conversation.copy(
            messages = listOf(ChatMessage("user", "☀".repeat(ConversationExports.MAX_BYTES / 2)))
        )
        assertThrows(IOException::class.java) { ConversationExports.encode(huge) }
    }

    @Test fun cancellationStopsSerializationBeforeReturningAnyArchive() {
        var checks = 0
        val conversation =
            Conversation("conversation", "Fixture", listOf(ChatMessage("user", "Reference ".repeat(8192))))
        assertThrows(CancellationException::class.java) { ConversationExports.encode(conversation) { ++checks < 2 } }
        assertTrue(checks >= 2)
    }
}
