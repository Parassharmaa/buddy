package dev.buddy.core.scheduling

import dev.buddy.core.ModelEndpoint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class StatusAddress private constructor(val url: String, val origin: String) {
    override fun toString(): String = "StatusAddress(redacted)"
    companion object {
        fun parse(value: String, localHttp: Boolean = false): StatusAddress {
            require(value.length in 1..4096 && value == value.trim())
            require(
                value.none {
                    it.isISOControl() || it == '\\' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
                }
            )
            val authority = value.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
            require('@' !in authority)
            val parsed = requireNotNull(value.toHttpUrlOrNull())
            require(
                parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null &&
                    parsed.fragment == null
            )
            val canonical = parsed.newBuilder().host(parsed.host.removeSuffix(".")).build()
            val origin = canonical.newBuilder().encodedPath(
                "/"
            ).query(null).fragment(null).build().toString().removeSuffix("/")
            ModelEndpoint.parse(origin, localHttp)
            return StatusAddress(canonical.toString(), origin)
        }
    }
}

@Serializable
enum class ScheduleRecurrence { DAILY }

@Serializable
enum class MissedCheckPolicy { SKIP }

@Serializable
data class ScheduleDefinition(
    val id: String,
    val label: String,
    val address: String,
    val hour: Int,
    val minute: Int,
    val zoneId: String,
    val followDeviceZone: Boolean = true,
    val localHttp: Boolean = false,
    val recurrence: ScheduleRecurrence = ScheduleRecurrence.DAILY,
    val missedPolicy: MissedCheckPolicy = MissedCheckPolicy.SKIP,
    val maximumRuntimeMillis: Long = 15_000
) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(
            label.isNotBlank() && label.length <= 80 && label.none {
                it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
            }
        )
        StatusAddress.parse(address, localHttp)
        LocalTime.of(hour, minute)
        ZoneId.of(zoneId)
        require(maximumRuntimeMillis in 1..15_000)
    }
    override fun toString(): String = "ScheduleDefinition(redacted)"
}

@Serializable
data class ScheduleOccurrence(val date: String, val zoneId: String, val atMillis: Long) {
    init {
        LocalDate.parse(date)
        ZoneId.of(zoneId)
        require(atMillis > 0)
        require(Instant.ofEpochMilli(atMillis).atZone(ZoneId.of(zoneId)).toLocalDate().toString() == date)
    }
}

@Serializable
data class WebsiteSchedule(
    val definition: ScheduleDefinition,
    val next: ScheduleOccurrence,
    val revision: Long = 1,
    val enabled: Boolean = true
) {
    init {
        require(revision > 0)
    }
    override fun toString(): String = "WebsiteSchedule(redacted)"
}

object ScheduleTime {
    const val MAX_SCHEDULES = 16
    const val LATE_WINDOW_MILLIS = 15 * 60_000L

    fun next(definition: ScheduleDefinition, now: Instant, deviceZone: ZoneId): ScheduleOccurrence {
        val zone = if (definition.followDeviceZone) deviceZone else ZoneId.of(definition.zoneId)
        var date = now.atZone(zone).toLocalDate()
        repeat(370) {
            val local = date.atTime(definition.hour, definition.minute)
            // Skip a missing wall time; choose the first occurrence of an overlapping wall time.
            val instant = zone.rules.getValidOffsets(local).map { local.toInstant(it) }.minOrNull()
            if (instant != null &&
                instant > now
            ) {
                return ScheduleOccurrence(date.toString(), zone.id, instant.toEpochMilli())
            }
            date = date.plusDays(1)
        }
        error("No valid occurrence within the planning bound")
    }

    fun preview(definition: ScheduleDefinition, now: Instant, deviceZone: ZoneId): List<ScheduleOccurrence> {
        var after = now
        return List(3) {
            next(definition, after, deviceZone).also { after = Instant.ofEpochMilli(it.atMillis) }
        }
    }
}
