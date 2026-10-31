package dev.buddy

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.browser.WebReadResult
import dev.buddy.browser.WebReadStatus
import dev.buddy.core.Conversation
import dev.buddy.core.ModelConfig
import dev.buddy.pdf.PdfSourceReader
import dev.buddy.storage.EncryptedStore
import dev.buddy.ui.SourceController
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfSourceDeviceTest {
    private fun document(): ByteArray {
        val pdf = PdfDocument()
        try {
            repeat(22) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(512, 512, index + 1).create())
                page.canvas.drawText("Buddy PDF fixture ${index + 1}", 32f, 64f, Paint().apply { textSize = 24f })
                pdf.finishPage(page)
            }
            return ByteArrayOutputStream().use { output ->
                pdf.writeTo(output)
                output.toByteArray()
            }
        } finally {
            pdf.close()
        }
    }

    @Test(timeout = 60_000)
    fun isolatedSelectableTextIsBoundedAndRequiresSourceConfirmation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                val workspace = UUID.randomUUID().toString()
                val bytes = document()
                val artifact = try {
                    store.artifacts.importFile(
                        workspace,
                        "fixture.pdf",
                        "application/pdf",
                        Uri.parse("content://fixture/pdf"),
                        bytes
                    )
                } finally {
                    bytes.fill(0)
                }
                val state = MutableStateFlow(
                    BuddyState(
                        loaded = true,
                        configured = true,
                        conversation = Conversation(workspace, "PDF fixture"),
                        artifacts = listOf(artifact)
                    )
                )
                val reader = PdfSourceReader(context, store)
                if (Build.VERSION.SDK_INT >= 35) {
                    val checked = reader.read(workspace, artifact.id)
                    assertEquals(16, checked.pagesRead)
                    assertEquals(22, checked.totalPages)
                }
                val controller = SourceController(
                    store,
                    scope,
                    state,
                    { ModelConfig("https://pdf-fixture.invalid/v1", "fixture", "") },
                    { WebReadResult.Suppressed(WebReadStatus.UNSUPPORTED) },
                    reader::read
                )
                withContext(Dispatchers.Main) { controller.file(artifact.id) }
                if (Build.VERSION.SDK_INT < 35) {
                    assertEquals(null, controller.state.value.pending)
                    assertTrue(state.value.error.orEmpty().contains("Android 15"))
                } else {
                    val preview = withTimeout(20_000) { controller.state.first { it.pending != null } }
                    val source = requireNotNull(preview.pending).source
                    assertTrue(source.text.contains("Buddy"))
                    assertTrue(source.truncated)
                    assertTrue(source.text.length <= 32_768)
                    assertTrue(state.value.draftSources.isEmpty())
                    withContext(Dispatchers.Main) { assertTrue(controller.confirm()) }
                    assertEquals(source, state.value.draftSources.single())
                    withContext(Dispatchers.Main) { controller.remove(artifact.id) }
                    assertTrue(state.value.draftSources.isEmpty())
                }
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                store.clear()
            }
        }
    }

    @Test(timeout = 30_000)
    fun malformedPdfReturnsNoContentAndDoesNotLeaveTheReaderLocked() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                val workspace = UUID.randomUUID().toString()
                val bad = store.artifacts.importFile(
                    workspace,
                    "bad.pdf",
                    "application/pdf",
                    Uri.parse("content://fixture/bad-pdf"),
                    "%PDF-1.7\nmalformed-private-canary".toByteArray()
                )
                val reader = PdfSourceReader(context, store)
                val failure = runCatching { reader.read(workspace, bad.id) }.exceptionOrNull()
                assertTrue(failure != null)
                assertFalse(failure.toString().contains("malformed-private-canary"))
                if (Build.VERSION.SDK_INT >= 35) {
                    val bytes = document()
                    val valid = try {
                        store.artifacts.importFile(
                            workspace,
                            "good.pdf",
                            "application/pdf",
                            Uri.parse("content://fixture/good-pdf"),
                            bytes
                        )
                    } finally {
                        bytes.fill(0)
                    }
                    val excerpt = reader.read(workspace, valid.id)
                    assertEquals(16, excerpt.pagesRead)
                    assertEquals(22, excerpt.totalPages)
                    assertTrue(excerpt.truncated)
                }
            } finally {
                store.clear()
            }
        }
    }
}
