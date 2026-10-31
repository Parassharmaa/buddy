package dev.buddy.core

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPolicyTest {
    @Test fun preferencesRetainUnicodeWhileRejectingCredentialsAndCodes() {
        assertEquals("Reply in हिन्दी", MemoryPolicy.fact(" Reply in हिन्दी "))
        assertEquals("Use 👩‍💻 examples", MemoryPolicy.fact("Use 👩‍💻 examples"))
        assertEquals("Keep responses under 200 tokens", MemoryPolicy.fact("Keep responses under 200 tokens"))
        for (fact in listOf(
            "My password is synthetic", "My pass" + 0x200d.toChar() + "word is synthetic",
            "PIN 1234", "OTP 123456", "API key sk-abcdefghijklmnop",
            "Bearer example-credential", "Card 4111 1111 1111 1111", "Code １２３４５６", "Code ١٢٣٤٥٦",
            "ｐａｓｓｗｏｒｄ is synthetic", "Go to https://user:password@example.org", "Use \u202Ehidden text"
        )) {
            assertTrue(runCatching { MemoryPolicy.fact(fact) }.isFailure)
        }
    }

    @Test fun sourceReferencesRemainBoundToAnExplicitConversationMessage() {
        val conversation = UUID.randomUUID().toString()
        assertEquals("$conversation:3", MemoryPolicy.sourceMessage(conversation, 3))
        assertTrue(runCatching { MemoryPolicy.sourceMessage("../", 3) }.isFailure)
        assertTrue(runCatching { MemoryPolicy.sourceMessage(conversation, -1) }.isFailure)
        val fact = SavedMemory(UUID.randomUUID().toString(), "Prefer short replies", "$conversation:3", 10, 10)
        MemoryPolicy.validate(fact)
        assertFalse(fact.toString().contains("short replies"))
    }

    @Test fun deletedRecordsCannotKeepTheFactOrItsPreviousVersions() {
        val memory = SavedMemory(
            UUID.randomUUID().toString(),
            "",
            MemoryPolicy.sourceMessage(UUID.randomUUID().toString(), 0),
            10,
            20,
            deletedAt = 20
        )
        MemoryPolicy.validate(memory)
        assertTrue(runCatching { MemoryPolicy.validate(memory.copy(fact = "old preference")) }.isFailure)
        assertTrue(
            runCatching {
                MemoryPolicy.validate(memory.copy(revisions = listOf(MemoryRevision("old preference", 10))))
            }.isFailure
        )
    }
}
