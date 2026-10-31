package dev.buddy.scheduling

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.buddy.BuddyApplication
import dev.buddy.core.scheduling.ScheduleCheckState
import dev.buddy.core.scheduling.StatusAddress
import dev.buddy.core.scheduling.WebsiteStatusClient
import dev.buddy.storage.ScheduleAdmission
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WebsiteScheduleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val application = applicationContext as BuddyApplication
        val repository = application.store.schedules
        val id = inputData.getString("rule") ?: return Result.failure()
        val admission = repository.prepare(
            id,
            inputData.getLong("revision", -1),
            inputData.getLong("due", -1),
            Instant.now(),
            ZoneId.systemDefault()
        )
        if (admission is ScheduleAdmission.Reschedule) {
            application.schedules.enqueue(admission.schedule)
            return Result.success()
        }
        if (admission !is ScheduleAdmission.Claimed) return Result.success()
        val rule = admission.schedule
        var outcome = ScheduleCheckState.INTERRUPTED
        var code: Int? = null
        try {
            val context = currentCoroutineContext()
            code = withTimeoutOrNull(rule.definition.maximumRuntimeMillis) {
                if (!repository.current(rule) || !context.isActive) return@withTimeoutOrNull null
                WebsiteStatusClient().use { client ->
                    client.check(StatusAddress.parse(rule.definition.address, rule.definition.localHttp)) {
                        context.isActive && !isStopped
                    }
                }
            }
            outcome = if (code == null) ScheduleCheckState.TIMED_OUT else ScheduleCheckState.HTTP
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            outcome = ScheduleCheckState.NETWORK_ERROR
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(10_000) {
                    val next = repository.finish(rule, outcome, code, Instant.now(), ZoneId.systemDefault())
                    if (next != null) application.schedules.enqueue(next)
                }
            }
        }
        return Result.success()
    }
}
