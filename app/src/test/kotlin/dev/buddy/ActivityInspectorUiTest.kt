package dev.buddy

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.buddy.core.Conversation
import dev.buddy.core.RunRecord
import dev.buddy.core.RunState
import dev.buddy.design.BuddyTheme
import dev.buddy.ui.Activity
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
class ActivityInspectorUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownOutcomesRemainVisibleAndDeletedHistoryLeavesNoInspectorContent() {
        val conversation = Conversation(
            "history-fixture",
            "Synthetic task",
            runs = listOf(
                RunRecord(
                    "run-fixture",
                    "history-fixture",
                    "Synthetic request",
                    RunState.UNKNOWN,
                    "Effect not verified",
                    0
                )
            )
        )
        val history = mutableStateOf(listOf(conversation))
        var opened: String? = null
        compose.setContent { BuddyTheme { Activity(history.value) { opened = it } } }
        compose.onNodeWithText("View task history").performClick()
        compose.onNodeWithText("Outcome unknown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Effect not verified").assertIsDisplayed()
        compose.onNodeWithText("Open conversation").performScrollTo().performClick()
        assertEquals("history-fixture", opened)
        compose.onNodeWithText("Outcome unknown").performScrollTo()
        val bitmap = compose.onRoot().captureToImage()
        val directory = File(requireNotNull(System.getProperty("buddy.capture.dir"))).apply { mkdirs() }
        File(directory, "12-task-history.png").outputStream().use { output ->
            bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        compose.runOnIdle { history.value = emptyList() }
        compose.onNodeWithText("A clear record of what happened.").assertIsDisplayed()
        compose.onNodeWithText("Synthetic request").assertDoesNotExist()
        compose.onNodeWithText("Effect not verified").assertDoesNotExist()
    }
}
