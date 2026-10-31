package dev.buddy.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.scheduling.ScheduleCheckState
import dev.buddy.core.scheduling.ScheduleDefinition
import dev.buddy.core.scheduling.ScheduleTime
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleRepositoryDeviceTest {
    private val zone = ZoneId.of("UTC")
    private val before = Instant.parse("2027-01-01T08:59:00Z")
    private fun definition() = ScheduleDefinition(
        UUID.randomUUID().toString(),
        "Synthetic status",
        "https://example.com/status",
        9,
        0,
        zone.id
    )

    @Test(timeout = 30_000)
    fun concurrentClaimsRunOnceAndAnInterruptedClaimIsNeverReplayed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                val repository = store.schedules
                val rule = repository.save(definition(), before, zone)
                val due = Instant.ofEpochMilli(rule.next.atMillis)
                val results = coroutineScope {
                    List(2) {
                        async { repository.prepare(rule.definition.id, rule.revision, rule.next.atMillis, due, zone) }
                    }
                        .map { it.await() }
                }
                assertEquals(1, results.count { it is ScheduleAdmission.Claimed })
                assertEquals(1, results.count { it is ScheduleAdmission.Reschedule })
                assertNull(repository.finish(rule, ScheduleCheckState.HTTP, 200, due.plusSeconds(1), zone))
                val next = requireNotNull(repository.get(rule.definition.id))
                assertTrue(next.next.atMillis > rule.next.atMillis)
                assertFalse(repository.current(rule))
                assertTrue(
                    repository.prepare(
                        rule.definition.id,
                        rule.revision,
                        rule.next.atMillis,
                        due,
                        zone
                    ) is ScheduleAdmission.Inactive
                )
            } finally {
                store.clear()
            }
        }
    }

    @Test(timeout = 30_000)
    fun expiredChecksAreSkippedAndClearPreventsLateCompletionFromRestoringPrivateData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                val repository = store.schedules
                val rule = repository.save(definition(), before, zone)
                val late = Instant.ofEpochMilli(rule.next.atMillis + ScheduleTime.LATE_WINDOW_MILLIS + 1)
                assertTrue(
                    repository.prepare(
                        rule.definition.id,
                        rule.revision,
                        rule.next.atMillis,
                        late,
                        zone
                    ) is ScheduleAdmission.Reschedule
                )
                val fresh = repository.save(definition(), before, zone)
                val due = Instant.ofEpochMilli(fresh.next.atMillis)
                assertTrue(
                    repository.prepare(
                        fresh.definition.id,
                        fresh.revision,
                        fresh.next.atMillis,
                        due,
                        zone
                    ) is ScheduleAdmission.Claimed
                )
                repository.reconcile(
                    fresh.definition.id,
                    due.plusMillis(ScheduleTime.LATE_WINDOW_MILLIS + 1),
                    zone
                )
                assertEquals(
                    ScheduleCheckState.INTERRUPTED,
                    repository.observe().first().checks.single { it.scheduleId == fresh.definition.id }.state
                )
                store.clear()
                assertNull(repository.finish(fresh, ScheduleCheckState.HTTP, 200, due.plusSeconds(1), zone))
                assertTrue(repository.list().isEmpty())
                val paused = repository.save(definition(), before, zone)
                repository.enabled(paused.definition.id, false, before, zone)
                assertFalse(repository.save(paused.definition.copy(label = "Edited"), before, zone).enabled)
                assertTrue(store.list("schedule-run", "schedule-run:").isEmpty())
            } finally {
                store.clear()
            }
        }
    }
}
