package dev.buddy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.browser.WebReadResult
import dev.buddy.browser.WebReadStatus
import dev.buddy.core.ChatSourceKind
import dev.buddy.core.ModelConfig
import dev.buddy.core.guides.GuideLookup
import dev.buddy.guides.InstalledAppGuides
import dev.buddy.storage.EncryptedStore
import dev.buddy.ui.SourceController
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
class AppGuideDeviceTest {
    @Test(timeout = 30_000)
    fun installedVersionIsCheckedAndGuideSharingRequiresConfirmationForTheCurrentModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        EncryptedStore(context).use { store ->
            store.clear()
            try {
                var lookup = InstalledAppGuides.current(context)
                assertTrue(lookup is GuideLookup.Available)
                val guide = (lookup as GuideLookup.Available).guide
                var config = ModelConfig("https://guide-fixture.invalid/v1", "fixture", "")
                val state = MutableStateFlow(BuddyState(loaded = true, configured = true))
                val controller = SourceController(
                    store,
                    scope,
                    state,
                    { config },
                    { WebReadResult.Suppressed(WebReadStatus.UNSUPPORTED) },
                    readGuide = { lookup }
                )
                withContext(Dispatchers.Main) { controller.guide(guide.id) }
                val preview = withTimeout(5_000) { controller.state.first { it.pending != null } }
                assertEquals(ChatSourceKind.GUIDE, preview.pending?.source?.kind)
                assertEquals(guide.text, preview.pending?.source?.text)
                assertTrue(state.value.draftSources.isEmpty())
                withContext(Dispatchers.Main) { assertTrue(controller.confirm()) }
                assertEquals(guide.text, state.value.draftSources.single().text)
                withContext(Dispatchers.Main) {
                    controller.remove(guide.id)
                    controller.guide(guide.id)
                }
                withTimeout(5_000) { controller.state.first { it.pending != null } }
                config = config.copy(model = "changed")
                withContext(Dispatchers.Main) { assertFalse(controller.confirm()) }
                assertTrue(state.value.draftSources.isEmpty())
                lookup = GuideLookup.VersionChanged
                withContext(Dispatchers.Main) { controller.guide(guide.id) }
                withTimeout(5_000) { controller.state.first { !it.preparing } }
                assertEquals(null, controller.state.value.pending)
                assertTrue(state.value.draftSources.isEmpty())
            } finally {
                scope.coroutineContext[Job]?.cancelAndJoin()
                store.clear()
            }
        }
    }
}
