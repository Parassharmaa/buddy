package dev.buddy.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import dev.buddy.core.ActionJournal
import dev.buddy.core.ActionRecord
import dev.buddy.core.Approval
import dev.buddy.core.DispatchState
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "records")
data class EncryptedRecord(@PrimaryKey val id: String, val category: String, val body: ByteArray, val updatedAt: Long)

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun get(id: String): EncryptedRecord?

    @Query("SELECT * FROM records WHERE category = :category ORDER BY updatedAt DESC")
    fun observe(category: String): Flow<List<EncryptedRecord>>

    @Query("SELECT * FROM records WHERE category = :category")
    suspend fun all(category: String): List<EncryptedRecord>

    @Query("SELECT * FROM records WHERE category = :category AND id LIKE :prefix || '%' ORDER BY updatedAt DESC")
    suspend fun byPrefix(category: String, prefix: String): List<EncryptedRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: EncryptedRecord)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun remove(id: String)

    @Query("DELETE FROM records WHERE category = :category AND updatedAt < :before")
    suspend fun expire(category: String, before: Long)

    @Query("DELETE FROM records")
    suspend fun clear()
}

@Database(entities = [EncryptedRecord::class], version = 1, exportSchema = true)
abstract class BuddyDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
}

enum class HistoryRetention(val days: Int?) {
    ONE_DAY(1),
    SEVEN_DAYS(7),
    THIRTY_DAYS(30),
    KEEP_UNTIL_DELETED(null)
}

class EncryptedStore(context: Context) :
    ActionJournal,
    java.io.Closeable {
    private companion object {
        val privateDataOperations = Mutex()
    }
    override fun close() {
        database.close()
    }
    private val database = Room.databaseBuilder(context, BuddyDatabase::class.java, "buddy.db").build()
    private val dao = database.records()
    val artifacts: WorkspaceArtifacts by lazy { WorkspaceArtifacts(context.applicationContext, this) }
    val memories: MemoryRepository by lazy { MemoryRepository(this) }
    val schedules: ScheduleRepository by lazy { ScheduleRepository(this) }
    internal suspend fun <T> mutatePrivateRecords(operation: suspend () -> T): T = privateDataOperations.withLock {
        database.withTransaction { operation() }
    }
    internal suspend fun eraseMemories() = mutatePrivateRecords {
        dao.all("memory").forEach { dao.remove(it.id) }
        dao.remove("memory-enabled")
    }
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("buddy.records.v1", null) as SecretKey?)
            ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        "buddy.records.v1",
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(
                            KeyProperties.BLOCK_MODE_GCM
                        ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
                )
                generateKey()
            }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private fun encrypt(id: String, value: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key)
        updateAAD(id.toByteArray())
        iv + doFinal(value.toByteArray())
    }
    private fun decrypt(record: EncryptedRecord): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(record.body.size >= 28)
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.body.copyOfRange(0, 12)))
        updateAAD(record.id.toByteArray())
        doFinal(record.body.copyOfRange(12, record.body.size)).toString(Charsets.UTF_8)
    }
    suspend fun put(id: String, category: String, value: String) = withContext(Dispatchers.IO) {
        dao.put(EncryptedRecord(id, category, encrypt(id, value), System.currentTimeMillis()))
    }
    suspend fun get(id: String): String? = withContext(Dispatchers.IO) { dao.get(id)?.let(::decrypt) }
    fun observe(category: String): Flow<List<Pair<String, String>>> = dao.observe(category).map { rows ->
        rows.map {
            it.id to
                decrypt(it)
        }
    }.flowOn(Dispatchers.IO)
    suspend fun list(category: String, prefix: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        require(prefix.isNotEmpty() && prefix.none { it == '%' || it == '_' })
        dao.byPrefix(category, prefix).map { it.id to decrypt(it) }
    }
    suspend fun remove(id: String) {
        val workspace = id.removePrefix("conversation:")
        if (id.startsWith("conversation:") &&
            runCatching { java.util.UUID.fromString(workspace).toString() == workspace }.getOrDefault(false)
        ) {
            artifacts.mutateRecords {
                database.withTransaction {
                    dao.remove(id)
                    dao.byPrefix("artifact", "artifact:$workspace:").forEach { dao.remove(it.id) }
                }
            }
        } else {
            dao.remove(id)
        }
    }
    suspend fun clear() = privateDataOperations.withLock { artifacts.clearRecordsAndFiles { dao.clear() } }
    suspend fun historyRetention(): HistoryRetention = get("history-retention")?.let(HistoryRetention::valueOf)
        ?: HistoryRetention.THIRTY_DAYS
    suspend fun setHistoryRetention(retention: HistoryRetention) {
        put("history-retention", "settings", retention.name)
        expireHistory()
    }
    suspend fun expireHistory() {
        artifacts.mutateRecords {
            val days = historyRetention().days
            if (days != null) {
                val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
                database.withTransaction {
                    val expired = dao.all("conversation").filter { it.updatedAt < cutoff }
                    expired.forEach { conversation ->
                        val workspace = conversation.id.removePrefix("conversation:")
                        if (runCatching {
                                java.util.UUID.fromString(workspace).toString() == workspace
                            }.getOrDefault(false)
                        ) {
                            dao.byPrefix("artifact", "artifact:$workspace:").forEach { dao.remove(it.id) }
                        }
                    }
                    dao.expire("conversation", cutoff)
                    // Action records and unresolved barriers survive ordinary history retention.
                }
            }
        }
    }
    override suspend fun unresolved(operationKey: String): Boolean = dao.get("barrier:$operationKey") != null
    override suspend fun begin(record: ActionRecord, approval: Approval?) = database.withTransaction {
        check(!unresolved(record.proposal.operationKey))
        if (approval != null) {
            check(dao.get("consumed:${approval.id}") == null)
            put("consumed:${approval.id}", "consent", "consumed")
        }
        put("action:${record.proposal.id}", "action", json.encodeToString(record))
        put("barrier:${record.proposal.operationKey}", "barrier", record.proposal.runId)
    }
    override suspend fun update(record: ActionRecord) = database.withTransaction {
        put("action:${record.proposal.id}", "action", json.encodeToString(record))
        if (record.state == DispatchState.NOT_DISPATCHED ||
            (record.state == DispatchState.VERIFIED && record.evidenceIds.isNotEmpty())
        ) {
            dao.remove("barrier:${record.proposal.operationKey}")
        }
    }
    override suspend fun markUncertain(record: ActionRecord) = database.withTransaction {
        put("action:${record.proposal.id}", "action", json.encodeToString(record.copy(state = DispatchState.UNCERTAIN)))
        put("barrier:${record.proposal.operationKey}", "barrier", record.proposal.runId)
    }
}
