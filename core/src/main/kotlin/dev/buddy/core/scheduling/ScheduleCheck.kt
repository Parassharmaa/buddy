package dev.buddy.core.scheduling

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleCheckState { STARTED, HTTP, NETWORK_ERROR, TIMED_OUT, SKIPPED, INTERRUPTED }

@Serializable
data class ScheduleCheck(
    val scheduleId: String,
    val date: String,
    val startedAt: Long,
    val state: ScheduleCheckState,
    val finishedAt: Long? = null,
    val httpCode: Int? = null
) {
    init {
        require(UUID.fromString(scheduleId).toString() == scheduleId)
        LocalDate.parse(date)
        require(startedAt > 0)
        require((state == ScheduleCheckState.STARTED) == (finishedAt == null))
        require(finishedAt == null || finishedAt >= startedAt)
        require((state == ScheduleCheckState.HTTP) == (httpCode != null))
        require(httpCode == null || httpCode in 100..599)
    }
}
