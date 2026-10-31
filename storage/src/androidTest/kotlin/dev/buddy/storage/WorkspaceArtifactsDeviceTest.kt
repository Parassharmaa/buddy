package dev.buddy.storage

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceArtifactsDeviceTest {
    @Test fun encryptedFilesRespectOwnershipBudgetsIntegrityAndSymlinkBoundaries() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EncryptedStore(context).use { store ->
            store.clear()
            val workspace = UUID.randomUUID().toString()
            val outside = File(context.cacheDir, "artifact-outside-fixture.txt")
            try {
                store.put("conversation:$workspace", "conversation", "fixture")
                val bytes = "Synthetic Unicode file · こんにちは · café".toByteArray()
                val artifact = store.artifacts.importFile(workspace, "notes.txt", "text/plain", SOURCE, bytes)
                val encrypted = File(context.noBackupFilesDir, "artifacts/$workspace/${artifact.id}")
                assertFalse(encrypted.readBytes().toString(Charsets.ISO_8859_1).contains("Synthetic Unicode"))
                val budget = ArtifactReadBudget(bytes.size)
                assertArrayEquals(bytes, store.artifacts.read(workspace, artifact.id, budget))
                assertTrue(runCatching { store.artifacts.read(workspace, artifact.id, budget) }.isFailure)
                assertTrue(
                    runCatching {
                        store.artifacts.read(UUID.randomUUID().toString(), artifact.id, ArtifactReadBudget())
                    }.isFailure
                )
                assertTrue(runCatching { store.artifacts.read("../", artifact.id, ArtifactReadBudget()) }.isFailure)
                val ciphertext = encrypted.readBytes()
                ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
                encrypted.writeBytes(ciphertext)
                assertTrue(runCatching { store.artifacts.read(workspace, artifact.id, ArtifactReadBudget()) }.isFailure)
                encrypted.delete()
                outside.writeText("outside sentinel")
                Files.createSymbolicLink(encrypted.toPath(), outside.toPath())
                assertTrue(runCatching { store.artifacts.read(workspace, artifact.id, ArtifactReadBudget()) }.isFailure)
                store.clear()
                assertTrue(outside.readText() == "outside sentinel")
                assertFalse(Files.exists(encrypted.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            } finally {
                store.clear()
                outside.delete()
            }
        }
    }

    @Test fun quotasAndRetentionRemoveCiphertextAlongsideConversationReferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EncryptedStore(context).use { store ->
            store.clear()
            val workspace = UUID.randomUUID().toString()
            try {
                store.put("conversation:$workspace", "conversation", "fixture")
                suspend fun rejected(name: String, type: String, bytes: ByteArray) {
                    assertTrue(
                        runCatching {
                            store.artifacts.importFile(workspace, name, type, SOURCE, bytes)
                        }.isFailure
                    )
                }
                rejected("../notes.txt", "text/plain", byteArrayOf(65))
                rejected("code.apk", "application/vnd.android.package-archive", byteArrayOf(65))
                rejected("fake.png", "image/png", "not an image".toByteArray())
                rejected("broken.txt", "text/plain", byteArrayOf(0xc3.toByte()))
                rejected("deep.json", "application/json", ("[".repeat(40) + "0" + "]".repeat(40)).toByteArray())
                rejected("empty.txt", "text/plain", byteArrayOf())
                rejected("large.txt", "text/plain", ByteArray(WorkspaceArtifacts.MAX_FILE_BYTES + 1) { 65 })
                val bytes = ByteArray(WorkspaceArtifacts.MAX_FILE_BYTES) { 65 }
                repeat(4) { store.artifacts.importFile(workspace, "notes.txt", "text/plain", SOURCE, bytes) }
                rejected("extra.txt", "text/plain", byteArrayOf(65))
                val files = store.artifacts.list(workspace).map {
                    File(context.noBackupFilesDir, "artifacts/$workspace/${it.id}")
                }
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath("buddy.db").path,
                    null,
                    SQLiteDatabase.OPEN_READWRITE
                ).use {
                    it.execSQL(
                        "UPDATE records SET updatedAt = ? WHERE id = ?",
                        arrayOf<Any>(1L, "conversation:$workspace")
                    )
                }
                store.setHistoryRetention(HistoryRetention.ONE_DAY)
                assertTrue(store.artifacts.list(workspace).isEmpty())
                assertTrue(files.none(File::exists))
                assertTrue(
                    runCatching {
                        store.artifacts.importFile(workspace, "resurrected.txt", "text/plain", SOURCE, byteArrayOf(65))
                    }.isFailure
                )
                store.put("conversation:$workspace", "conversation", "fixture")
                val final = store.artifacts.importFile(workspace, "final.txt", "text/plain", SOURCE, byteArrayOf(65))
                store.remove("conversation:$workspace")
                assertFalse(File(context.noBackupFilesDir, "artifacts/$workspace/${final.id}").exists())
                assertTrue(store.artifacts.list(workspace).isEmpty())
            } finally {
                store.clear()
            }
        }
    }

    companion object {
        private val SOURCE = Uri.parse("content://dev.buddy.fixture/documents/synthetic")
    }
}
