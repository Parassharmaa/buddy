package dev.buddy

import android.net.Uri
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.core.Conversation
import dev.buddy.files.ArtifactProvider
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateFileProviderDeviceTest {
    @Test(timeout = 30_000)
    fun privateFilesAreSeekableReadOnlyAndCannotBeReopenedAfterClear() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as BuddyApplication).store
        store.clear()
        try {
            val workspace = UUID.randomUUID().toString()
            store.put(
                "conversation:$workspace",
                "conversation",
                Json.encodeToString(Conversation(workspace, "File fixture"))
            )
            val bytes = "Synthetic private file · café".toByteArray()
            val artifact = store.artifacts.importFile(
                workspace,
                "fixture.txt",
                "text/plain",
                Uri.parse("content://fixture/seekable"),
                bytes
            )
            val uri = Uri.Builder().scheme("content").authority(context.packageName + ".artifacts")
                .appendPath(workspace).appendPath(artifact.id).build()
            requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")).use { descriptor ->
                assertEquals(bytes.size.toLong(), Os.fstat(descriptor.fileDescriptor).st_size)
                assertEquals(2L, Os.lseek(descriptor.fileDescriptor, 2, OsConstants.SEEK_SET))
                val selected = ByteArray(4)
                assertEquals(4, Os.read(descriptor.fileDescriptor, selected, 0, selected.size))
                assertArrayEquals(bytes.copyOfRange(2, 6), selected)
                assertTrue(runCatching { Os.write(descriptor.fileDescriptor, byteArrayOf(0), 0, 1) }.isFailure)
                ArtifactProvider.revokeAll(context)
                store.clear()
                assertTrue(runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.isFailure)
                selected.fill(0)
            }
            bytes.fill(0)
        } finally {
            ArtifactProvider.revokeAll(context)
            store.clear()
        }
    }
}
