package dev.buddy.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.MemoryPolicy
import dev.buddy.core.SavedMemory
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryRepositoryDeviceTest {
    private fun source() = MemoryPolicy.sourceMessage(UUID.randomUUID().toString(), 0)

    @Test(timeout = 30_000)
    fun preferencesRequireOptInAndDeletionPurgesTheFactAndBoundedEditHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                val memories = store.memories
                assertFalse(memories.enabled())
                assertTrue(runCatching { memories.save("Prefer concise replies", source(), enable = false) }.isFailure)
                val original = memories.save("Prefer concise replies", source(), enable = true)
                assertTrue(memories.enabled())
                repeat(12) { memories.edit(original.id, "Prefer response style $it") }
                val edited = requireNotNull(memories.get(original.id))
                assertEquals(original.sourceMessageId, edited.sourceMessageId)
                assertEquals(MemoryPolicy.MAX_REVISIONS, edited.revisions.size)
                assertEquals("Prefer response style 11", edited.fact)
                for (suffix in listOf("", "-wal", "-shm")) {
                    val database = context.getDatabasePath("buddy.db$suffix")
                    if (database.exists()) {
                        assertFalse(database.readBytes().toString(Charsets.ISO_8859_1).contains(edited.fact))
                    }
                }
                memories.setEnabled(false)
                assertFalse(memories.enabled())
                assertNotNull(memories.get(original.id))
                memories.delete(original.id)
                assertEquals(null, memories.get(original.id))
                val tombstone = Json.decodeFromString<SavedMemory>(requireNotNull(store.get("memory:${original.id}")))
                assertTrue(tombstone.fact.isEmpty() && tombstone.revisions.isEmpty())
                assertNotNull(tombstone.deletedAt)
                memories.clear()
                assertEquals(null, store.get("memory:${original.id}"))
                assertFalse(memories.enabled())
            } finally {
                store.clear()
            }
        }
    }

    @Test(timeout = 60_000)
    fun concurrentSavesCannotExceedTheQuotaAndClearWaitsForTheCurrentMutation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                store.memories.setEnabled(true)
                repeat(MemoryPolicy.MAX_FACTS - 1) {
                    store.memories.save("Preference $it", source(), enable = false)
                }
                val saved = coroutineScope {
                    List(2) { index ->
                        async { runCatching { store.memories.save("Final preference $index", source(), false) } }
                    }.awaitAll()
                }
                assertEquals(1, saved.count { it.isSuccess })
                assertEquals(MemoryPolicy.MAX_FACTS, store.list("memory", "memory:").size)
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val writer = launch {
                        store.mutateMemory {
                            entered.complete(Unit)
                            release.await()
                            store.put("memory-enabled", "memory-settings", "true")
                        }
                    }
                    try {
                        withTimeout(5_000) { entered.await() }
                        val clear = async { store.clear() }
                        delay(50)
                        assertFalse(clear.isCompleted)
                        release.complete(Unit)
                        withTimeout(5_000) {
                            writer.join()
                            clear.await()
                        }
                        assertTrue(store.list("memory", "memory:").isEmpty())
                        assertFalse(store.memories.enabled())
                    } finally {
                        release.complete(Unit)
                    }
                }
            } finally {
                store.clear()
            }
        }
    }
}
