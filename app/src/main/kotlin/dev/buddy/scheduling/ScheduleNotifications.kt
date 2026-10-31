package dev.buddy.scheduling

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.buddy.MainActivity
import dev.buddy.R
import dev.buddy.core.scheduling.ScheduleCheckState
import dev.buddy.core.scheduling.WebsiteSchedule
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ScheduleNotifications(private val context: Context, private val current: suspend (WebsiteSchedule) -> Boolean) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val operations = Mutex()
    suspend fun completed(rule: WebsiteSchedule, state: ScheduleCheckState, code: Int?) = operations.withLock {
        if (!rule.definition.notify || !current(rule) ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            !manager.areNotificationsEnabled()
        ) {
            return@withLock
        }
        val outcome = when (state) {
            ScheduleCheckState.HTTP -> "HTTP ${requireNotNull(code)}"
            ScheduleCheckState.NETWORK_ERROR -> "Website unavailable"
            ScheduleCheckState.TIMED_OUT -> "Check timed out"
            else -> return@withLock
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Website checks", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).putExtra("buddy.destination", "automations")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val public = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_buddy)
            .setContentTitle("Buddy").setContentText("A website check finished. Open Buddy to view it.").build()
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_buddy)
            .setContentTitle("Website check finished").setContentText("${rule.definition.label}: $outcome")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setLocalOnly(
                true
            ).setCategory(Notification.CATEGORY_STATUS).setContentIntent(open).setAutoCancel(true).build()
        try {
            manager.notify(tag(rule.definition.id), 1, notification)
        } catch (_: SecurityException) {
            // Android can revoke notification permission between the check and delivery.
        }
    }
    suspend fun remove(id: String) = operations.withLock { manager.cancel(tag(id), 1) }
    suspend fun clear() = operations.withLock {
        manager.activeNotifications.filter {
            it.tag?.startsWith(PREFIX) == true
        }.forEach { manager.cancel(it.tag, it.id) }
    }
    private fun tag(id: String) = "$PREFIX$id"
    companion object {
        private const val CHANNEL = "website-check-results"
        private const val PREFIX = "website-check:"
    }
}
