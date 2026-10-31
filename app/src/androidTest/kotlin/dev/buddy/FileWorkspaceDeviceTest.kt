package dev.buddy

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.connection.PhoneConnection
import dev.buddy.files.DocumentAccess
import dev.buddy.qualification.DocumentFixtureProvider
import dev.buddy.storage.ArtifactReadBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileWorkspaceDeviceTest {
    @Test(timeout = 20_000)
    fun cancellingAStalledSelectionCannotRestoreFilesAfterOpeningANewConversation() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as BuddyApplication).store
        val models = ViewModelStore()
        store.clear()
        DocumentFixtureProvider.release()
        var nextSource: Uri? = null
        try {
            val model = withContext(Dispatchers.Main) {
                BuddyViewModel(store, PhoneConnection(context), DocumentAccess(context)).also {
                    models.put("files", it)
                }
            }
            withTimeout(5_000) { model.state.first { it.loaded } }
            val original = model.state.value.conversation.id
            withContext(Dispatchers.Main) {
                model.importDocument(Uri.parse("content://${context.packageName}.documents-fixture/stalled"))
            }
            withTimeout(5_000) { while (!DocumentFixtureProvider.waiting) delay(20) }
            withContext(Dispatchers.Main) { model.cancelFileOperation() }
            withTimeout(2_000) { model.state.first { !it.importing } }
            withContext(Dispatchers.Main) { model.newChat() }
            val current = model.state.value.conversation.id
            assertTrue(current != original)
            val selected = requireNotNull(
                context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "buddy-after-cancellation.txt")
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                )
            )
            nextSource = selected
            DocumentAccess(context).write(selected, "New synthetic document".toByteArray())
            withContext(Dispatchers.Main) { model.importDocument(selected) }
            withTimeout(5_000) { model.state.first { !it.importing && it.artifacts.size == 1 } }
            DocumentFixtureProvider.release()
            store.expireHistory()
            assertEquals(current, model.state.value.conversation.id)
            assertTrue(model.state.value.artifacts.single().workspace == current)
            assertTrue(store.artifacts.list(original).isEmpty())
            assertEquals(null, store.get("conversation:$original"))
        } finally {
            DocumentFixtureProvider.release()
            nextSource?.let { context.contentResolver.delete(it, null, null) }
            withContext(Dispatchers.Main) { models.clear() }
            store.clear()
        }
    }

    @Test(timeout = 45_000)
    fun selectedContentImportsSharesExportsAndDeletesWithoutAPlaintextCache() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("buddyPairingFixture") == "true")
        assumeTrue(Build.HARDWARE == "ranchu" && Build.FINGERPRINT.contains("sdk_gphone"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as BuddyApplication).store
        val resolver = context.contentResolver
        val models = ViewModelStore()
        val documents = DocumentAccess(context)
        val bytes = "Synthetic document · こんにちは · café\n".repeat(8192).toByteArray()
        val created = mutableListOf<Uri>()
        fun destination(name: String): Uri = requireNotNull(
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            )
        ).also { created += it }
        store.clear()
        try {
            val source = destination("buddy-synthetic-source.txt")
            documents.write(source, bytes)
            resolver.update(source, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val model = withContext(Dispatchers.Main) {
                BuddyViewModel(store, PhoneConnection(context), documents).also { models.put("files", it) }
            }
            withTimeout(5_000) { model.state.first { it.loaded } }
            withContext(Dispatchers.Main) { model.importDocument(source) }
            val imported = withTimeout(10_000) {
                model.state.first { !it.importing && it.artifacts.size == 1 }
            }
            assertEquals(null, imported.error)
            val artifact = imported.artifacts.single()
            assertEquals(bytes.size, artifact.bytes)
            assertArrayEquals(bytes, store.artifacts.read(artifact.workspace, artifact.id, ArtifactReadBudget()))
            assertTrue(imported.conversation.messages.isEmpty())
            withTimeout(5_000) { model.state.first { state -> state.history.any { it.id == artifact.workspace } } }
            withContext(Dispatchers.Main) {
                model.newChat()
                model.openConversation(artifact.workspace)
            }
            withTimeout(5_000) { model.state.first { it.artifacts.any { file -> file.id == artifact.id } } }
            val shared = Uri.Builder().scheme("content").authority(context.packageName + ".artifacts")
                .appendPath(artifact.workspace).appendPath(artifact.id).build()
            assertEquals("text/plain", resolver.getType(shared))
            resolver.query(shared, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                assertTrue(it.moveToFirst())
                assertEquals(artifact.displayName, it.getString(0))
                assertEquals(bytes.size, it.getInt(1))
            } ?: error("File metadata unavailable")
            assertArrayEquals(bytes, requireNotNull(resolver.openInputStream(shared)).use { it.readBytes() })
            // Recipients can abandon a read while the producer is writing a larger-than-pipe payload.
            requireNotNull(resolver.openInputStream(shared)).use { assertTrue(it.read() >= 0) }
            assertTrue(runCatching { resolver.openFileDescriptor(shared, "w")?.close() }.isFailure)
            val exported = destination("buddy-synthetic-export.txt")
            withContext(Dispatchers.Main) { model.exportArtifact(artifact.id, exported) }
            withTimeout(10_000) {
                while (true) {
                    val size = resolver.query(exported, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst()) it.getLong(0) else 0L
                    }
                    if (!model.state.value.exporting && size == bytes.size.toLong()) break
                    delay(50)
                }
            }
            assertEquals(null, model.state.value.error)
            assertArrayEquals(bytes, requireNotNull(resolver.openInputStream(exported)).use { it.readBytes() })
            withContext(Dispatchers.Main) { model.removeArtifact(artifact.id) }
            withTimeout(5_000) { model.state.first { !it.importing && it.artifacts.isEmpty() } }
            assertFalse(store.artifacts.list(artifact.workspace).any { it.id == artifact.id })
            assertTrue(runCatching { resolver.openInputStream(shared)?.close() }.isFailure)
            assertTrue(store.get("conversation:${artifact.workspace}") != null)
        } finally {
            withContext(Dispatchers.Main) { models.clear() }
            created.forEach { resolver.delete(it, null, null) }
            bytes.fill(0)
            store.clear()
        }
    }
}
