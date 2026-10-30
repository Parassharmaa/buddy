package dev.buddy

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.buddy.storage.EncryptedStore
import java.util.concurrent.TimeUnit

class BuddyApplication :
    Application(),
    Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
    val store by lazy { EncryptedStore(this) }
    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "history-retention",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HistoryRetentionWorker>(1, TimeUnit.DAYS).build()
        )
    }
}
