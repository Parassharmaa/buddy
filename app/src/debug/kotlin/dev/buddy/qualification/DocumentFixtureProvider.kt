package dev.buddy.qualification

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

// Debug-only unexported stalled source. It has no files or user data.
class DocumentFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "text/plain"
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
        addRow(arrayOf<Any>("stalled-fixture.txt", -1L))
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(mode == "r" && uri.path == "/stalled")
        val pipe = ParcelFileDescriptor.createPipe()
        synchronized(writers) { writers += pipe[1] }
        return pipe[0]
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        error("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read only")

    companion object {
        private val writers = mutableListOf<ParcelFileDescriptor>()
        val waiting: Boolean get() = synchronized(writers) { writers.isNotEmpty() }
        fun release() = synchronized(writers) {
            writers.forEach { runCatching { it.close() } }
            writers.clear()
        }
    }
}
