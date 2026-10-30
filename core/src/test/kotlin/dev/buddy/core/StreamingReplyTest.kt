package dev.buddy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamingReplyTest {
    @Test fun joinsToolFragmentsAndPreservesCallId() {
        val parser = StreamingReply()
        parser.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"phone_observe","arguments":"{\"run"}}]}}]}"""
        )
        parser.accept(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"_id\":\"r1\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"total_tokens":42}}"""
        )
        val result = parser.finish()
        assertEquals("call_1", result.message.toolCalls?.single()?.id)
        assertEquals("phone_observe", result.message.toolCalls?.single()?.function?.name)
        assertEquals("""{"run_id":"r1"}""", result.message.toolCalls?.single()?.function?.arguments)
        assertEquals(42, result.tokens)
    }

    @Test fun rejectsTruncatedArgumentsAndMissingFinish() {
        val parser = StreamingReply()
        parser.accept("""{"choices":[{"delta":{"content":"Partial"}}]}""")
        assertThrows(ModelFailure::class.java) { parser.finish() }
        val invalid = StreamingReply()
        invalid.accept(
            """{"choices":[{"message":{"tool_calls":[{"id":"a","function":{"name":"tap","arguments":"{"}}]},"finish_reason":"tool_calls"}]}"""
        )
        assertThrows(IllegalArgumentException::class.java) { invalid.finish() }
    }

    @Test fun supportsPlainJsonAndRejectsDuplicateCallIds() {
        val parser = StreamingReply()
        parser.accept("""{"choices":[{"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}]}""")
        assertEquals("Hello", parser.finish().message.content)
        val duplicate = StreamingReply()
        duplicate.accept(
            """{"choices":[{"message":{"tool_calls":[{"id":"a","function":{"name":"read","arguments":"{}"}},{"id":"a","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
        )
        assertThrows(IllegalArgumentException::class.java) { duplicate.finish() }
    }

    @Test fun anEmptyCompletionCannotBecomeASuccessfulRun() {
        val parser = StreamingReply()
        parser.accept("""{"choices":[{"message":{"content":""},"finish_reason":"stop"}]}""")
        assertThrows(ModelFailure::class.java) { parser.finish() }
    }

    @Test fun unknownToolKindsAndNonAssistantRolesCannotBecomeFunctionCalls() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamingReply().accept(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","type":"custom","function":{"name":"read","arguments":"{}"}}]}}]}"""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            StreamingReply().accept("""{"choices":[{"message":{"role":"system","content":"untrusted"}}]}""")
        }
    }

    @Test fun invalidUsageCannotReduceTheCumulativeBudget() {
        for (usage in listOf("-1", "1.5", "\"42\"")) {
            assertThrows(IllegalArgumentException::class.java) {
                StreamingReply().accept("""{"choices":[],"usage":{"total_tokens":$usage}}""")
            }
        }
        val parser = StreamingReply()
        parser.accept(
            """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],"usage":{"total_tokens":100}}"""
        )
        assertThrows(IllegalArgumentException::class.java) {
            parser.accept("""{"choices":[],"usage":{"total_tokens":90}}""")
        }
    }
}
