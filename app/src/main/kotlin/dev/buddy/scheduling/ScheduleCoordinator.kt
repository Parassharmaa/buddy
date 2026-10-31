package dev.buddy.scheduling

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dev.buddy.core.scheduling.WebsiteSchedule
import dev.buddy.storage.ScheduleRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ScheduleCoordinator(private val context: Context, private val repository: ScheduleRepository) {
    private val manager get() = WorkManager.getInstance(context)
    suspend fun enqueue(rule: WebsiteSchedule) {
        if (!rule.enabled) return
        val delay = maxOf(0, Duration.between(Instant.now(), Instant.ofEpochMilli(rule.next.atMillis)).toMillis())
        val request = OneTimeWorkRequestBuilder<WebsiteScheduleWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    "rule" to rule.definition.id,
                    "revision" to rule.revision,
                    "due" to rule.next.atMillis
                )
            )
            .addTag(TAG).addTag(tag(rule.definition.id)).build()
        manager.enqueueUniqueWork(
            "${tag(rule.definition.id)}:${rule.revision}:${rule.next.atMillis}",
            ExistingWorkPolicy.KEEP,
            request
        ).await()
    }
    suspend fun cancel(id: String) {
        manager.cancelAllWorkByTag(tag(id)).await()
        (context.applicationContext as dev.buddy.BuddyApplication).scheduleNotifications.remove(id)
        updateReceiver()
    }
    suspend fun cancelAll() {
        manager.cancelAllWorkByTag(TAG).await()
        (context.applicationContext as dev.buddy.BuddyApplication).scheduleNotifications.clear()
    }
    suspend fun reconcile() {
        repository.list().forEach {
            repository.reconcile(it.definition.id, Instant.now(), ZoneId.systemDefault())?.let { current ->
                enqueue(current)
            }
        }
        updateReceiver()
    }
    suspend fun updateReceiver() {
        val enabled = repository.list().any { it.enabled }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, ScheduleTimeReceiver::class.java),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )
    }
    private fun tag(id: String) = "$TAG:$id"
    companion object {
        private const val TAG = "website-schedules"
    }
}
