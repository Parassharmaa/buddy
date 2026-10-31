package dev.buddy.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRevisionsTest {
    @Test fun physicalContentChangesDoNotInvalidateAPrivateDisplayButUnknownChangesDo() {
        val revisions = DisplayRevisions()
        revisions.changed()
        val initial = revisions.current(7)
        revisions.changed(0)
        assertEquals(initial, revisions.current(7))
        assertTrue(revisions.current(0) > initial)
        revisions.changed(7)
        val privateChange = revisions.current(7)
        assertTrue(privateChange > initial)
        revisions.changed()
        assertTrue(revisions.current(0) > privateChange)
        assertEquals(revisions.current(0), revisions.current(7))
    }

    @Test fun boundedDisplayTrackingInvalidatesAllScopesWhenItMustDiscardEntries() {
        val revisions = DisplayRevisions()
        repeat(16) { revisions.changed(it) }
        val before = (0..16).map(revisions::current)
        revisions.changed(16)
        (0..16).forEach { assertTrue(revisions.current(it) > before[it]) }
        val fresh = revisions.current(16)
        revisions.changed(0)
        assertEquals(fresh, revisions.current(16))
        revisions.changed(-1)
        assertTrue(revisions.current(16) > fresh)
    }
}
