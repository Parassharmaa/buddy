package dev.buddy

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import dev.buddy.core.scheduling.ScheduleCheckState
import dev.buddy.core.scheduling.ScheduleDefinition
import dev.buddy.core.scheduling.ScheduleTime
import dev.buddy.core.scheduling.WebsiteSchedule
import dev.buddy.scheduling.ScheduleNotifications
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScheduleNotificationsTest {
    private fun rule(notify: Boolean): WebsiteSchedule {
        val definition = ScheduleDefinition(
            UUID.randomUUID().toString(),
            "Private fixture label",
            "https://example.com/private-path",
            9,
            0,
            "UTC",
            notify = notify
        )
        return WebsiteSchedule(definition, ScheduleTime.next(definition, Instant.now(), ZoneId.of("UTC")))
    }

    @Test fun optInPermissionAndCurrentRuleAreRequiredAndLockScreenContentIsRedacted() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val manager = application.getSystemService(NotificationManager::class.java)
        val permissions = shadowOf(application)
        var current = true
        val notifications = ScheduleNotifications(application) { current }
        val selected = rule(true)
        permissions.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.completed(selected, ScheduleCheckState.HTTP, 204)
        assertTrue(manager.activeNotifications.isEmpty())
        permissions.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.completed(rule(false), ScheduleCheckState.HTTP, 204)
        assertTrue(manager.activeNotifications.isEmpty())
        current = false
        notifications.completed(selected, ScheduleCheckState.HTTP, 204)
        assertTrue(manager.activeNotifications.isEmpty())
        current = true
        notifications.completed(selected, ScheduleCheckState.HTTP, 204)
        val posted = manager.activeNotifications.single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertTrue(posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("HTTP 204"))
        val public = requireNotNull(posted.publicVersion)
        assertFalse(public.extras.toString().contains("Private fixture label"))
        assertFalse(posted.extras.toString().contains("private-path"))
        assertTrue(posted.flags and Notification.FLAG_LOCAL_ONLY != 0)
        notifications.remove(selected.definition.id)
        assertTrue(manager.activeNotifications.isEmpty())
        notifications.completed(selected, ScheduleCheckState.HTTP, 204)
        notifications.clear()
        current = false
        notifications.completed(selected, ScheduleCheckState.HTTP, 204)
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun clearingWaitsForAnInFlightPostAndLeavesNoNotificationBehind() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = application.getSystemService(NotificationManager::class.java)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val notifications = ScheduleNotifications(application) {
            entered.complete(Unit)
            release.await()
            true
        }
        val posting = async { notifications.completed(rule(true), ScheduleCheckState.HTTP, 204) }
        entered.await()
        val clearing = async { notifications.clear() }
        yield()
        assertFalse(clearing.isCompleted)
        release.complete(Unit)
        posting.await()
        clearing.await()
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
