package dev.buddy.storage

import dev.buddy.core.MemoryPolicy
import dev.buddy.core.MemoryRevision
import dev.buddy.core.SavedMemory
import java.util.UUID
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class MemorySnapshot(val enabled: Boolean = false, val memories: List<SavedMemory> = emptyList())

class MemoryRepository internal constructor(private val records: EncryptedStore) {
    suspend fun clear() = records.eraseMemories()
    private val json = Json { ignoreUnknownKeys = true }
    private fun entries(rows: List<Pair<String, String>>): List<SavedMemory> = rows.map { (id, value) ->
        json.decodeFromString<SavedMemory>(value).also {
            MemoryPolicy.validate(it)
            require(id == "memory:${it.id}")
        }
    }
    fun observe() = combine(records.observe("memory"), records.observe("memory-settings")) { rows, settings ->
        val enabled = settings.singleOrNull { it.first == "memory-enabled" }?.second == "true"
        MemorySnapshot(enabled, entries(rows).filter { it.deletedAt == null }.sortedByDescending { it.changedAt })
    }
    suspend fun enabled(): Boolean = records.get("memory-enabled") == "true"
    suspend fun setEnabled(enabled: Boolean) = records.mutateMemory {
        records.put("memory-enabled", "memory-settings", enabled.toString())
    }
    suspend fun get(id: String): SavedMemory? {
        require(UUID.fromString(id).toString() == id)
        return records.get("memory:$id")?.let { json.decodeFromString<SavedMemory>(it) }?.also(MemoryPolicy::validate)
            ?.takeIf { it.deletedAt == null }
    }
    suspend fun save(fact: String, sourceMessageId: String, enable: Boolean): SavedMemory = records.mutateMemory {
        check(enabled() || enable)
        val existing = entries(records.list("memory", "memory:"))
        check(existing.count { it.deletedAt == null } < MemoryPolicy.MAX_FACTS)
        val now = System.currentTimeMillis()
        val memory = SavedMemory(UUID.randomUUID().toString(), MemoryPolicy.fact(fact), sourceMessageId, now, now)
        MemoryPolicy.validate(memory)
        records.put("memory:${memory.id}", "memory", json.encodeToString(memory))
        if (enable) records.put("memory-enabled", "memory-settings", "true")
        existing.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }.drop(MemoryPolicy.MAX_FACTS)
            .forEach { records.remove("memory:${it.id}") }
        memory
    }
    suspend fun edit(id: String, fact: String) = records.mutateMemory {
        val existing = requireNotNull(get(id))
        val value = MemoryPolicy.fact(fact)
        if (value == existing.fact) return@mutateMemory
        val next = existing.copy(
            fact = value,
            changedAt = maxOf(System.currentTimeMillis(), existing.changedAt),
            revisions = (
                existing.revisions + MemoryRevision(
                    existing.fact,
                    existing.changedAt
                )
                ).takeLast(MemoryPolicy.MAX_REVISIONS)
        )
        MemoryPolicy.validate(next)
        records.put("memory:$id", "memory", json.encodeToString(next))
    }
    suspend fun delete(id: String) = records.mutateMemory {
        val existing = get(id) ?: return@mutateMemory
        val now = maxOf(System.currentTimeMillis(), existing.changedAt)
        val tombstone = existing.copy(fact = "", changedAt = now, revisions = emptyList(), deletedAt = now)
        records.put("memory:$id", "memory", json.encodeToString(tombstone))
    }
}
