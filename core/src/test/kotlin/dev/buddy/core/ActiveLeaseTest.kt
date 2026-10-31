package dev.buddy.core

import dev.buddy.core.connection.ActiveLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveLeaseTest {
    @Test fun renewsOnlyAfterFreshProofAndStopsOnCancellation() = runTest {
        val calls = mutableListOf<String>()
        val lease = ActiveLease(
            30_000,
            { testScheduler.currentTime },
            {
                calls += "proof"
                testScheduler.currentTime + 30_000
            },
            {
                calls += "renew"
                testScheduler.currentTime + 30_000
            },
            { calls += "release" }
        )
        val job = async { lease.maintain() }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("proof", "renew"), calls)
        assertEquals(40_000, lease.deadline)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(50_000, lease.deadline)
        job.cancel()
        runCurrent()
        assertFalse(lease.isCurrent)
        assertEquals(listOf("proof", "renew", "proof", "renew", "release"), calls)
    }

    @Test fun rejectionNeverRenewsAndReleasesOnce() = runTest {
        var renewals = 0
        var releases = 0
        val lease = ActiveLease(
            30_000,
            { testScheduler.currentTime },
            { error("authorization rejected") },
            {
                renewals++
                40_000
            },
            { releases++ }
        )
        assertTrue(runCatching { lease.maintain() }.isFailure)
        assertEquals(0, renewals)
        assertEquals(1, releases)
        assertFalse(lease.isCurrent)
        assertTrue(runCatching { lease.maintain() }.isFailure)
        assertEquals(1, releases)
    }

    @Test fun stuckProofIsBoundedAndCleanupSurvivesCancellation() = runTest {
        var renewals = 0
        var releases = 0
        val lease = ActiveLease(
            30_000,
            { testScheduler.currentTime },
            { awaitCancellation() },
            {
                renewals++
                40_000
            },
            {
                delay(50)
                releases++
            }
        )
        assertTrue(runCatching { lease.maintain() }.exceptionOrNull() is CancellationException)
        assertEquals(25_050, testScheduler.currentTime)
        assertEquals(0, renewals)
        assertEquals(1, releases)
        assertFalse(lease.isCurrent)
    }

    @Test fun staleOrUnboundedProofCannotExtendLease() = runTest {
        for (proof in listOf(30_000L, 100_000L)) {
            var renewed = false
            val startedAt = testScheduler.currentTime
            val lease = ActiveLease(
                startedAt + 30_000,
                { testScheduler.currentTime },
                { startedAt + proof },
                {
                    renewed = true
                    testScheduler.currentTime + 30_000
                },
                {}
            )
            assertTrue(runCatching { lease.maintain() }.isFailure)
            assertFalse(renewed)
            assertFalse(lease.isCurrent)
        }
    }

    @Test fun slowRenewalCannotResurrectAnExpiredLease() = runTest {
        val lease = ActiveLease(
            12_000,
            { testScheduler.currentTime },
            { 30_000 },
            {
                delay(13_000)
                40_000
            },
            {}
        )
        assertTrue(runCatching { lease.maintain() }.isFailure)
        assertEquals(12_000, testScheduler.currentTime)
        assertFalse(lease.isCurrent)
    }
}
