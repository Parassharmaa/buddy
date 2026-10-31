package dev.buddy.storage

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WorkspaceArtifact(
    val id: String,
    val workspace: String,
    val displayName: String,
    val mimeType: String,
    val bytes: Int,
    val sourceUri: String,
    val createdAt: Long
) {
    override fun toString(): String = "WorkspaceArtifact(redacted)"
}

class ArtifactReadBudget(private val maximumBytes: Int = WorkspaceArtifacts.MAX_FILE_BYTES) {
    init {
        require(maximumBytes in 1..WorkspaceArtifacts.MAX_FILE_BYTES)
    }
    private var consumed = 0

    @Synchronized internal fun consume(bytes: Int) {
        require(bytes >= 0 && bytes <= maximumBytes - consumed) { "File read budget exceeded" }
        consumed += bytes
    }
}

class WorkspaceArtifacts(context: Context, private val records: EncryptedStore) {
    private val root = File(context.noBackupFilesDir.canonicalFile, "artifacts")
    private val json = Json { ignoreUnknownKeys = true }
    private val key: SecretKey by lazy {
        synchronized(keyLock) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey("buddy.artifacts.v1", null) as SecretKey?)
                ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec.Builder(
                            "buddy.artifacts.v1",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
                    )
                    generateKey()
                }
        }
    }

    private fun uuid(value: String) {
        require(UUID.fromString(value).toString() == value) { "Invalid workspace reference" }
    }
    private fun prefix(workspace: String): String {
        uuid(workspace)
        return "artifact:$workspace:"
    }
    private fun directory(workspace: String): File {
        uuid(workspace)
        check(root.isDirectory || root.mkdirs())
        check(!Files.isSymbolicLink(root.toPath()))
        val directory = File(root, workspace)
        check(directory.isDirectory || directory.mkdir())
        check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == root.canonicalFile)
        return directory
    }
    private fun file(workspace: String, id: String): File {
        uuid(id)
        return File(directory(workspace), id).also {
            check(
                listOf(it, File(it.path + ".new"), File(it.path + ".bak")).none { candidate ->
                    Files.isSymbolicLink(candidate.toPath())
                }
            )
        }
    }
    private fun validate(artifact: WorkspaceArtifact) {
        uuid(artifact.id)
        uuid(artifact.workspace)
        require(artifact.bytes in 1..MAX_FILE_BYTES && artifact.createdAt > 0)
        require(
            artifact.displayName.length in 1..128 && artifact.displayName.none {
                it.isISOControl() || it == '/' || it == '\\' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
            }
        )
        val extensions = types[artifact.mimeType] ?: error("Unsupported file type")
        require(artifact.displayName.substringAfterLast('.', "").lowercase() in extensions)
        require(artifact.sourceUri.length in 1..2048 && Uri.parse(artifact.sourceUri).scheme == "content")
    }
    private suspend fun listed(workspace: String): List<WorkspaceArtifact> =
        records.list("artifact", prefix(workspace)).map {
            json.decodeFromString<WorkspaceArtifact>(it.second).also { artifact ->
                validate(artifact)
                check(artifact.workspace == workspace && it.first == prefix(workspace) + artifact.id)
            }
        }
    suspend fun list(workspace: String): List<WorkspaceArtifact> = withContext(Dispatchers.IO) {
        operations.withLock { listed(workspace) }
    }

    suspend fun importFile(
        workspace: String,
        displayName: String,
        mimeType: String,
        source: Uri,
        bytes: ByteArray
    ): WorkspaceArtifact = withContext(Dispatchers.IO) {
        operations.withLock {
            val artifact = WorkspaceArtifact(
                UUID.randomUUID().toString(),
                workspace,
                displayName,
                mimeType,
                bytes.size,
                source.toString(),
                System.currentTimeMillis()
            )
            validate(artifact)
            check(records.get("conversation:$workspace") != null) { "Conversation unavailable" }
            val contents = bytes.copyOf()
            try {
                validateContents(mimeType, contents)
                val existing = listed(workspace)
                require(
                    existing.size < MAX_FILES && existing.sumOf {
                        it.bytes.toLong()
                    } + bytes.size <= MAX_WORKSPACE_BYTES
                )
                currentCoroutineContext().ensureActive()
                val destination = AtomicFile(file(workspace, artifact.id))
                val aad = prefix(workspace) + artifact.id
                val encrypted = Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, key)
                    updateAAD(aad.toByteArray(Charsets.UTF_8))
                    iv + doFinal(contents)
                }
                val output = destination.startWrite()
                var committed = false
                try {
                    output.write(encrypted)
                    currentCoroutineContext().ensureActive()
                    destination.finishWrite(output)
                    committed = true
                    check(destination.baseFile.isFile && destination.baseFile.length() == encrypted.size.toLong())
                    records.put(aad, "artifact", json.encodeToString(artifact))
                    artifact
                } catch (failure: Throwable) {
                    withContext(NonCancellable) {
                        if (!committed) {
                            destination.failWrite(output)
                        } else {
                            runCatching { if (records.get(aad) == null) destination.delete() }
                        }
                    }
                    throw failure
                }
            } finally {
                contents.fill(0)
            }
        }
    }

    suspend fun read(workspace: String, id: String, budget: ArtifactReadBudget): ByteArray =
        withContext(Dispatchers.IO) {
            operations.withLock {
                val aad = prefix(workspace) + id
                uuid(id)
                val artifact = requireNotNull(records.get(aad)) { "File unavailable" }.let {
                    json.decodeFromString<WorkspaceArtifact>(it)
                }
                validate(artifact)
                check(artifact.workspace == workspace && artifact.id == id)
                budget.consume(artifact.bytes)
                val source = file(workspace, id)
                check(Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS))
                check(source.length() == artifact.bytes.toLong() + 28)
                val encrypted = source.inputStream().use { it.readNBytes(MAX_FILE_BYTES + 29) }
                check(encrypted.size == artifact.bytes + 28)
                currentCoroutineContext().ensureActive()
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 0, 12))
                    updateAAD(aad.toByteArray(Charsets.UTF_8))
                    doFinal(encrypted, 12, encrypted.size - 12)
                }.also { check(it.size == artifact.bytes) }
            }
        }

    suspend fun delete(workspace: String, id: String) = withContext(Dispatchers.IO) {
        operations.withLock {
            val destination = file(workspace, id)
            records.remove(prefix(workspace) + id)
            AtomicFile(destination).delete()
            check(
                listOf(destination, File(destination.path + ".new"), File(destination.path + ".bak")).none {
                    Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS)
                }
            ) { "File cleanup incomplete" }
        }
    }

    internal suspend fun clearRecordsAndFiles(clearRecords: suspend () -> Unit) = withContext(Dispatchers.IO) {
        operations.withLock {
            clearRecords()
            if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(
                    root.toPath(),
                    object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        override fun visitFile(
                            path: java.nio.file.Path,
                            attributes: java.nio.file.attribute.BasicFileAttributes
                        ): java.nio.file.FileVisitResult {
                            Files.delete(path)
                            return java.nio.file.FileVisitResult.CONTINUE
                        }
                        override fun postVisitDirectory(
                            path: java.nio.file.Path,
                            failure: java.io.IOException?
                        ): java.nio.file.FileVisitResult {
                            if (failure != null) throw failure
                            Files.delete(path)
                            return java.nio.file.FileVisitResult.CONTINUE
                        }
                    }
                )
            }
        }
    }

    internal suspend fun mutateRecords(mutation: suspend () -> Unit) = withContext(Dispatchers.IO) {
        operations.withLock {
            mutation()
            pruneFilesLocked()
        }
    }

    private suspend fun pruneFilesLocked() {
        val retained = records.list("artifact", "artifact:").map {
            json.decodeFromString<WorkspaceArtifact>(it.second).also(::validate)
        }.map { file(it.workspace, it.id).toPath() }.toSet()
        if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(!Files.isSymbolicLink(root.toPath()))
            Files.walkFileTree(
                root.toPath(),
                object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        path: java.nio.file.Path,
                        attributes: java.nio.file.attribute.BasicFileAttributes
                    ): java.nio.file.FileVisitResult {
                        if (path !in retained) Files.delete(path)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                }
            )
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        const val MAX_WORKSPACE_BYTES = 32L * 1024 * 1024
        private const val MAX_FILES = 16
        private val operations = Mutex()
        private val keyLock = Any()
        private val types = mapOf(
            "text/plain" to setOf("txt"),
            "text/markdown" to setOf("md", "markdown"),
            "application/json" to setOf("json"),
            "application/pdf" to setOf("pdf"),
            "image/png" to setOf("png"),
            "image/jpeg" to setOf("jpg", "jpeg"),
            "image/webp" to setOf("webp"),
            "image/avif" to setOf("avif"),
            "image/heic" to setOf("heic", "heif"),
            "image/heif" to setOf("heic", "heif")
        )
        private fun validateContents(mimeType: String, bytes: ByteArray) {
            fun starts(vararg expected: Int) = bytes.size >= expected.size && expected.indices.all {
                bytes[it].toInt() and 255 == expected[it]
            }
            when (mimeType) {
                "image/png" -> require(starts(137, 80, 78, 71, 13, 10, 26, 10))

                "image/jpeg" -> require(starts(255, 216, 255))

                "image/webp" -> require(
                    starts(82, 73, 70, 70) && bytes.size >= 12 &&
                        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
                )

                "image/avif", "image/heic", "image/heif" -> {
                    require(bytes.size >= 12 && bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()))
                    val brand = bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII)
                    require(
                        brand in if (mimeType == "image/avif") {
                            setOf("avif", "avis")
                        } else {
                            setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
                        }
                    )
                }

                "application/pdf" -> require(starts(37, 80, 68, 70, 45))

                else -> {
                    val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    require('\u0000' !in text)
                    if (mimeType == "application/json") {
                        require(bytes.size <= 256 * 1024)
                        boundedJson.parseToJsonElement(text)
                    }
                }
            }
        }

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val boundedJson = Json {
            maxNestingDepth = 32
            exceptionsWithDebugInfo = false
        }
    }
}
