package dev.buddy.core.agent

import dev.buddy.core.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationCompactorTest {
    @Test fun onlyOlderObservationDataIsCompactedAndSourceIdsRemain() {
        val instructions = listOf(ChatMessage("system", "stable"), ChatMessage("user", "Original request"))
        val action =
            ChatMessage(
                "tool",
                """{"status":"uncertain","kind":"action","operation_key":"effect"}""",
                toolCallId = "write"
            )
        val observations = (1..5).map { n ->
            ChatMessage(
                "tool",
                buildJsonObject {
                    put("status", "observed")
                    put("kind", "observation")
                    put("source_id", "o$n")
                    put("data", buildJsonObject { put("text", "historical-$n") })
                }.toString(),
                toolCallId = "read-$n"
            )
        }
        val source = instructions + action + observations
        val compacted = ObservationCompactor.compact(source)
        assertEquals(3, compacted.count)
        assertEquals(instructions + action, compacted.messages.take(3))
        assertEquals(observations.takeLast(2), compacted.messages.takeLast(2))
        assertEquals(source.map { it.toolCallId }, compacted.messages.map { it.toolCallId })
        val old = compacted.messages.drop(3).take(3)
        old.forEachIndexed { index, message ->
            val value = Json.parseToJsonElement(requireNotNull(message.content)).jsonObject
            assertFalse(value.containsKey("data"))
            assertEquals("\"o${index + 1}\"", value.getValue("source_id").toString())
            assertTrue(value.containsKey("compacted"))
        }
        assertEquals(0, ObservationCompactor.compact(compacted.messages).count)
    }
}
