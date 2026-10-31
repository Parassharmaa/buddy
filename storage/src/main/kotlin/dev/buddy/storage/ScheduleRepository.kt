package dev.buddy.storage

import dev.buddy.core.scheduling.ScheduleCheck
import dev.buddy.core.scheduling.ScheduleCheckState
import dev.buddy.core.scheduling.ScheduleDefinition
import dev.buddy.core.scheduling.ScheduleTime
import dev.buddy.core.scheduling.WebsiteSchedule
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ScheduleSnapshot(val schedules: List<WebsiteSchedule>, val checks: List<ScheduleCheck>)

sealed interface ScheduleAdmission {
    data class Claimed(val schedule: WebsiteSchedule) : ScheduleAdmission
    data class Reschedule(val schedule: WebsiteSchedule) : ScheduleAdmission
    data object Inactive : ScheduleAdmission
}

class ScheduleRepository internal constructor(private val records: EncryptedStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun reference(id: String): String {
        require(UUID.fromString(id).toString() == id)
        return "schedule:$id"
    }
    private fun checkReference(schedule: WebsiteSchedule) =
        "schedule-run:${schedule.definition.id}:${schedule.next.date}"
    private fun decode(id: String, value: String): WebsiteSchedule =
        json.decodeFromString<WebsiteSchedule>(value).also {
            require(id == reference(it.definition.id))
        }
    fun observe() = combine(records.observe("schedule"), records.observe("schedule-run")) { rules, checks ->
        ScheduleSnapshot(
            rules.map { decode(it.first, it.second) }.sortedBy { it.next.atMillis },
            checks.map { json.decodeFromString<ScheduleCheck>(it.second) }.sortedByDescending { it.startedAt }
        )
    }
    suspend fun list(): List<WebsiteSchedule> = records.list("schedule", "schedule:").map {
        decode(it.first, it.second)
    }
    suspend fun get(id: String): WebsiteSchedule? = records.get(reference(id))?.let { decode(reference(id), it) }
    private suspend fun put(schedule: WebsiteSchedule) {
        records.put(reference(schedule.definition.id), "schedule", json.encodeToString(schedule))
    }
    private suspend fun prune(now: Instant) {
        val before = now.toEpochMilli() - 7 * 24 * 60 * 60_000L
        records.list("schedule-run", "schedule-run:").forEach { (id, value) ->
            if (json.decodeFromString<ScheduleCheck>(value).startedAt < before) records.remove(id)
        }
    }
    private suspend fun advance(schedule: WebsiteSchedule, now: Instant, zone: ZoneId): WebsiteSchedule = schedule.copy(
        next = ScheduleTime.next(schedule.definition, now, zone),
        revision = Math.addExact(schedule.revision, 1)
    ).also {
        put(it)
        prune(now)
    }

    suspend fun save(definition: ScheduleDefinition, now: Instant, zone: ZoneId): WebsiteSchedule =
        records.mutatePrivateRecords {
            val previous = get(definition.id)
            check(previous != null || list().size < ScheduleTime.MAX_SCHEDULES)
            val next = ScheduleTime.next(definition, now, zone)
            WebsiteSchedule(
                definition,
                next,
                previous?.let { Math.addExact(it.revision, 1) } ?: 1,
                enabled = previous?.enabled ?: true
            ).also { put(it) }
        }

    suspend fun enabled(id: String, enabled: Boolean, now: Instant, zone: ZoneId): WebsiteSchedule? =
        records.mutatePrivateRecords {
            val previous = get(id) ?: return@mutatePrivateRecords null
            val next = previous.copy(
                enabled = enabled,
                next = if (enabled) ScheduleTime.next(previous.definition, now, zone) else previous.next,
                revision = Math.addExact(previous.revision, 1)
            )
            put(next)
            next
        }

    suspend fun delete(id: String) = records.mutatePrivateRecords {
        records.remove(reference(id))
        records.list("schedule-run", "schedule-run:$id:").forEach { records.remove(it.first) }
    }

    suspend fun reconcile(id: String, now: Instant, zone: ZoneId): WebsiteSchedule? = records.mutatePrivateRecords {
        val previous = get(id)?.takeIf { it.enabled } ?: return@mutatePrivateRecords null
        if ((previous.definition.followDeviceZone && previous.next.zoneId != zone.id) ||
            now.toEpochMilli() - previous.next.atMillis > ScheduleTime.LATE_WINDOW_MILLIS
        ) {
            val key = checkReference(previous)
            val existing = records.get(key)?.let { json.decodeFromString<ScheduleCheck>(it) }
            if (existing == null || existing.state == ScheduleCheckState.STARTED) {
                val started = existing?.startedAt ?: now.toEpochMilli()
                records.put(
                    key,
                    "schedule-run",
                    json.encodeToString(
                        ScheduleCheck(
                            id,
                            previous.next.date,
                            started,
                            if (existing == null) ScheduleCheckState.SKIPPED else ScheduleCheckState.INTERRUPTED,
                            maxOf(started, now.toEpochMilli())
                        )
                    )
                )
            }
            advance(previous, now, zone)
        } else {
            previous
        }
    }

    suspend fun prepare(id: String, revision: Long, due: Long, now: Instant, zone: ZoneId): ScheduleAdmission =
        records.mutatePrivateRecords {
            val schedule = get(id)?.takeIf { it.enabled && it.revision == revision && it.next.atMillis == due }
                ?: return@mutatePrivateRecords ScheduleAdmission.Inactive
            if (schedule.definition.followDeviceZone && schedule.next.zoneId != zone.id) {
                return@mutatePrivateRecords ScheduleAdmission.Reschedule(advance(schedule, now, zone))
            }
            if (now.toEpochMilli() < due) {
                val rearmed = schedule.copy(revision = Math.addExact(schedule.revision, 1)).also { put(it) }
                return@mutatePrivateRecords ScheduleAdmission.Reschedule(rearmed)
            }
            val key = checkReference(schedule)
            val existing = records.get(key)?.let { json.decodeFromString<ScheduleCheck>(it) }
            val late = now.toEpochMilli() - due > ScheduleTime.LATE_WINDOW_MILLIS
            if (existing != null || late) {
                if (existing == null || existing.state == ScheduleCheckState.STARTED) {
                    val finished = ScheduleCheck(
                        id,
                        schedule.next.date,
                        existing?.startedAt ?: now.toEpochMilli(),
                        if (late) ScheduleCheckState.SKIPPED else ScheduleCheckState.INTERRUPTED,
                        maxOf(now.toEpochMilli(), existing?.startedAt ?: now.toEpochMilli())
                    )
                    records.put(key, "schedule-run", json.encodeToString(finished))
                }
                return@mutatePrivateRecords ScheduleAdmission.Reschedule(advance(schedule, now, zone))
            }
            records.put(
                key,
                "schedule-run",
                json.encodeToString(
                    ScheduleCheck(id, schedule.next.date, now.toEpochMilli(), ScheduleCheckState.STARTED)
                )
            )
            ScheduleAdmission.Claimed(schedule)
        }

    suspend fun current(schedule: WebsiteSchedule): Boolean = get(schedule.definition.id)?.let {
        it.enabled && it.revision == schedule.revision && it.next == schedule.next
    } == true

    suspend fun finish(
        schedule: WebsiteSchedule,
        state: ScheduleCheckState,
        code: Int?,
        now: Instant,
        zone: ZoneId
    ): WebsiteSchedule? = records.mutatePrivateRecords {
        require(state != ScheduleCheckState.STARTED)
        if (!current(schedule)) return@mutatePrivateRecords null
        val key = checkReference(schedule)
        val previous = records.get(key)?.let { json.decodeFromString<ScheduleCheck>(it) }
            ?: return@mutatePrivateRecords null
        if (previous.state != ScheduleCheckState.STARTED) return@mutatePrivateRecords null
        val result = previous.copy(
            state = state,
            httpCode = code,
            finishedAt = maxOf(now.toEpochMilli(), previous.startedAt)
        )
        records.put(key, "schedule-run", json.encodeToString(result))
        advance(schedule, now, zone)
    }
}
