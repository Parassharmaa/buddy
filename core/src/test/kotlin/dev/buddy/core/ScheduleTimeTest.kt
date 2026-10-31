package dev.buddy.core

import dev.buddy.core.scheduling.ScheduleDefinition
import dev.buddy.core.scheduling.ScheduleTime
import dev.buddy.core.scheduling.StatusAddress
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTimeTest {
    private fun definition(zone: String, hour: Int, minute: Int) = ScheduleDefinition(
        UUID.randomUUID().toString(),
        "Website check",
        "https://example.org/status",
        hour,
        minute,
        zone,
        followDeviceZone = false
    )

    @Test fun nonexistentSpringTimeIsSkippedAndFallOverlapRunsOnlyOnce() {
        val zone = ZoneId.of("Europe/Berlin")
        val daily = definition(zone.id, 2, 30)
        val spring = ScheduleTime.next(daily, Instant.parse("2027-03-27T23:00:00Z"), zone)
        assertEquals("2027-03-29", spring.date)
        assertEquals(Instant.parse("2027-03-29T00:30:00Z").toEpochMilli(), spring.atMillis)
        val first = ScheduleTime.next(daily, Instant.parse("2027-10-30T22:00:00Z"), zone)
        assertEquals(Instant.parse("2027-10-31T00:30:00Z").toEpochMilli(), first.atMillis)
        val afterFirst = ScheduleTime.next(daily, Instant.parse("2027-10-31T00:45:00Z"), zone)
        assertEquals("2027-11-01", afterFirst.date)
    }

    @Test fun followingTheDeviceZoneChangesThePlanWhileAFixedZoneRemainsFixed() {
        val now = Instant.parse("2027-01-01T00:00:00Z")
        val daily = definition("UTC", 9, 0)
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals(
            Instant.parse("2027-01-01T09:00:00Z").toEpochMilli(),
            ScheduleTime.next(daily, now, kolkata).atMillis
        )
        val following = ScheduleTime.next(daily.copy(followDeviceZone = true), now, kolkata)
        assertEquals(Instant.parse("2027-01-01T03:30:00Z").toEpochMilli(), following.atMillis)
        assertEquals(kolkata.id, following.zoneId)
    }

    @Test fun previewsAreFutureOnlyAcrossYearBoundariesAndSkippedCalendarDates() {
        val zone = ZoneId.of("Pacific/Apia")
        val definition = definition(zone.id, 12, 0)
        val preview = ScheduleTime.preview(definition, Instant.parse("2011-12-29T23:00:00Z"), zone)
        assertEquals(listOf("2011-12-31", "2012-01-01", "2012-01-02"), preview.map { it.date })
        assertTrue(preview.zipWithNext().all { it.first.atMillis < it.second.atMillis })
    }

    @Test fun statusTargetsExcludeCredentialsQueriesFragmentsAndImplicitCleartext() {
        assertEquals("https://example.org/status", StatusAddress.parse("https://EXAMPLE.org/status").url)
        for (url in listOf(
            "https://user:secret@example.org",
            "https://example.org?key=private",
            "https://example.org/#fragment",
            "http://example.org",
            "file:///private",
            "http://127.0.0.1/status"
        )) {
            assertTrue(runCatching { StatusAddress.parse(url) }.isFailure)
        }
        assertEquals("http://127.0.0.1/status", StatusAddress.parse("http://127.0.0.1/status", localHttp = true).url)
    }
}
