package dev.buddy.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.buddy.BuddyApplication
import dev.buddy.storage.ArtifactReadBudget
import dev.buddy.storage.WorkspaceArtifact
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class ArtifactProvider : ContentProvider() {
    override fun onCreate() = true
    private fun metadata(uri: Uri): WorkspaceArtifact {
        val context = requireNotNull(context)
        val callerUid = Binder.getCallingUid()
        val callerPid = Binder.getCallingPid()
        if (callerUid != android.os.Process.myUid() &&
            context.checkUriPermission(uri, callerPid, callerUid, Intent.FLAG_GRANT_READ_URI_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("File access requires a temporary grant")
        }
        if (uri.scheme != "content" || uri.authority != context.packageName + ".artifacts" ||
            uri.query != null || uri.fragment != null || uri.pathSegments.size != 2
        ) {
            throw FileNotFoundException("File unavailable")
        }
        val (workspace, id) = uri.pathSegments
        if (runCatching { UUID.fromString(workspace).toString() == workspace && UUID.fromString(id).toString() == id }
                .getOrDefault(false).not()
        ) {
            throw FileNotFoundException("File unavailable")
        }
        return runBlocking(Dispatchers.IO) {
            val store = (context.applicationContext as BuddyApplication).store
            store.artifacts.list(workspace).singleOrNull { it.id == id }
                ?: throw FileNotFoundException("File unavailable")
        }
    }
    override fun getType(uri: Uri): String = metadata(uri).mimeType
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        require(selection == null && selectionArgs == null && sortOrder == null)
        val artifact = metadata(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        require(columns.size in 1..2 && columns.all { it in setOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE) })
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) artifact.displayName else artifact.bytes })
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val artifact = metadata(uri)
        val bytes = runBlocking(Dispatchers.IO) {
            (requireNotNull(context).applicationContext as BuddyApplication).store.artifacts.read(
                artifact.workspace,
                artifact.id,
                ArtifactReadBudget()
            )
        }
        return try {
            openPipeHelper(uri, artifact.mimeType, null, bytes) { descriptor, _, _, _, contents ->
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(requireNotNull(contents)) }
                } catch (_: IOException) {
                    // The recipient can close its read end at any time.
                } finally {
                    contents?.fill(0)
                }
            }
        } catch (failure: Exception) {
            bytes.fill(0)
            throw FileNotFoundException("File unavailable")
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")
}
