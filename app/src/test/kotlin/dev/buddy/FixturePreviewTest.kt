package dev.buddy

import dev.buddy.core.Bounds
import dev.buddy.core.Observation
import dev.buddy.core.UiElement
import dev.buddy.qualification.FixturePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixturePreviewTest {
    private val node = UiElement("editor", "Synthetic private draft", "dev.buddy.fixture", Bounds(0, 0, 100, 50))
    private val observation = Observation(
        "id", "run", 1, 1_000, "dev.buddy.fixture", 720, 1280,
        listOf(node), "fingerprint"
    )

    @Test fun protectedUncertainAndForeignContentNeverBecomesPreviewText() {
        val secret = node.copy(label = "private-preview-canary")
        val input = observation.copy(nodes = listOf(secret))
        listOf(
            input.copy(protected = true),
            input.copy(credentialSurface = true),
            input.copy(excludedOverlay = true),
            input.copy(app = "other.app"),
            input.copy(nodes = listOf(secret.copy(password = true))),
            input.copy(nodes = listOf(secret.copy(packageName = "other.app")))
        ).forEach { candidate ->
            val preview = FixturePreview.from(candidate)
            assertTrue(preview.hidden)
            assertNull(preview.text)
            assertFalse(preview.toString().contains("private-preview-canary"))
        }
    }

    @Test fun excerptIsBoundedWithoutSplittingUnicodeAndFreshnessExpires() {
        val text = "a".repeat(511) + "😀"
        val preview = FixturePreview.from(observation.copy(nodes = listOf(node.copy(label = text))))
        assertEquals("a".repeat(511), preview.text)
        assertTrue(preview.isFresh(1_000))
        assertTrue(preview.isFresh(3_000))
        assertFalse(preview.isFresh(3_001))
        assertFalse(preview.isFresh(999))
        assertFalse(preview.toString().contains("aaaa"))
    }
}
