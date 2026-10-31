package dev.buddy.core

import dev.buddy.core.guides.AppGuide
import dev.buddy.core.guides.AppGuideCatalog
import dev.buddy.core.guides.BundledAppGuides
import dev.buddy.core.guides.GuideLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGuideTest {
    @Test fun guidesMatchAnExactPackageAndTestedVersionAndAgeOutAfterUpdates() {
        val catalog = BundledAppGuides.catalog
        assertTrue(catalog.lookup("dev.buddy", 1) is GuideLookup.Available)
        assertEquals(GuideLookup.VersionChanged, catalog.lookup("dev.buddy", 2))
        assertEquals(GuideLookup.Unavailable, catalog.lookup("dev.buddy.lookalike", 1))
        assertEquals(GuideLookup.Unavailable, catalog.lookup("Dev.Buddy", 1))
        val guide = AppGuide("fixture", "dev.buddy", 1, 3, "Fixture", "Reference facts")
        assertThrows(IllegalArgumentException::class.java) { AppGuideCatalog(listOf(guide, guide)) }
        assertThrows(IllegalArgumentException::class.java) {
            AppGuideCatalog(listOf(guide, AppGuide("overlap", "dev.buddy", 3, 4, "Other", "Other facts")))
        }
    }

    @Test fun guideTextRemainsQuotedUserReferenceDataAndCannotChangeTheDestination() {
        val config = ModelConfig("https://example.com/v1", "fixture", "")
        val guide = (BundledAppGuides.catalog.lookup("dev.buddy", 1) as GuideLookup.Available).guide
        val source =
            ChatSource(
                guide.id,
                ChatSourceKind.GUIDE,
                guide.title,
                guide.text,
                false,
                SourceContext.destination(config)
            )
        val message = SourceContext.wireMessages(
            listOf(ChatMessage("user", "Explain the browser", sources = listOf(source))),
            config
        ).single()
        assertEquals("user", message.role)
        assertTrue(message.content.orEmpty().contains("untrusted data"))
        assertTrue(message.content.orEmpty().contains("\"kind\":\"guide\""))
        assertTrue(message.sources.isEmpty())
        assertThrows(ModelFailure::class.java) {
            SourceContext.wireMessages(
                listOf(ChatMessage("user", "Explain", sources = listOf(source))),
                config.copy(model = "changed")
            )
        }
    }
}
