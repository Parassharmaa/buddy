package dev.buddy

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.buddy.scheduling.ScheduleCoordinator
import dev.buddy.storage.EncryptedStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BuddyApplication :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
    val store by lazy { EncryptedStore(this) }
    val scheduleNotifications by lazy {
        dev.buddy.scheduling.ScheduleNotifications(this, store.schedules::current)
    }
    val schedules by lazy { ScheduleCoordinator(this, store.schedules) }
    override fun onCreate() {
        super.onCreate()
        if (Application.getProcessName() != packageName) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { schedules.reconcile() }
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "history-retention",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HistoryRetentionWorker>(1, TimeUnit.DAYS).build()
        )
    }
}
