package dev.buddy.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.buddy.BuddyApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ScheduleTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in
            setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(8_000) { (context.applicationContext as BuddyApplication).schedules.reconcile() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The next foreground launch reconciles encrypted records again.
            } finally {
                pending.finish()
            }
        }
    }
}
