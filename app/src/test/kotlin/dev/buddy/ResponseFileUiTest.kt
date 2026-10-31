package dev.buddy

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.buddy.core.ChatMessage
import dev.buddy.design.BuddyTheme
import dev.buddy.ui.Chat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResponseFileUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aNativeClickChoosesTheResponseAndFileWorkDisablesFurtherSaves() {
        val initial = BuddyState(loaded = true)
        val state = mutableStateOf(
            initial.copy(
                conversation = initial.conversation.copy(
                    messages = listOf(
                        ChatMessage("user", "Create a short draft"),
                        ChatMessage("assistant", "Synthetic draft\n\nPlan one small step, then review the result.")
                    )
                )
            )
        )
        val saved = mutableListOf<Pair<String, Int>>()
        compose.setContent {
            BuddyTheme {
                Chat(state.value, {}, {}, saveResponse = { workspace, index -> saved.add(workspace to index) })
            }
        }
        assertEquals(emptyList<Pair<String, Int>>(), saved)
        compose.onNodeWithText("Save to files").performScrollTo().performClick()
        assertEquals(listOf(initial.conversation.id to 1), saved)
        val directory = File(requireNotNull(System.getProperty("buddy.capture.dir"))).apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "13-save-response.png").outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        compose.runOnIdle { state.value = state.value.copy(importing = true) }
        compose.onNodeWithText("Save to files").assertIsNotEnabled()
    }
}
