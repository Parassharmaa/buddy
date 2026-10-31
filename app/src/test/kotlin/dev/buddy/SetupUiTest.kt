package dev.buddy

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupUiTest {
    private val captureRun = System.currentTimeMillis()

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun setupNavigationAndLocalScreenshots() {
        compose.onNodeWithText("A little help.\nMore room for you.").assertIsDisplayed()
        screenshot("01-welcome")
        compose.onNodeWithText("Set up Buddy").performScrollTo().performClick()
        compose.onNodeWithText("Models").performClick()
        compose.onNodeWithText("Bring your own model").assertIsDisplayed()
        compose.onNodeWithText("Buddy").assertIsDisplayed()
        compose.onNodeWithText("Save and test model").assertIsDisplayed()
        screenshot("02-model-setup")
        compose.onNodeWithText("Back to settings").performClick()
        compose.onNodeWithText("Privacy").performClick()
        compose.onNodeWithText("Conversation history").assertIsDisplayed()
        compose.onNodeWithText("1 day").performScrollTo().performClick()
        compose.onNodeWithText("Shorten history retention?").assertIsDisplayed()
        compose.onNodeWithText("Keep setting").performClick()
        compose.onNodeWithText("Delete all local data").performScrollTo().assertIsDisplayed()
        screenshot("03-privacy")
        compose.onNodeWithText("Delete all local data").performClick()
        compose.onNodeWithText("Keep data").performClick()
        compose.onNodeWithText("Delete all local data").assertIsDisplayed()
        compose.onNodeWithText("Back to settings").performScrollTo().performClick()
        compose.onNodeWithText("Phone connection").performClick()
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        screenshot("04-phone-setup")
        compose.onNodeWithText("Back to settings").performClick()
        compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Diagnostics").assertIsDisplayed()
        screenshot("05-diagnostics")
        compose.onNodeWithText("Back to settings").performScrollTo().performClick()
        compose.onNodeWithText("Memory").performScrollTo().performClick()
        compose.onNodeWithText("Save preferences").assertIsDisplayed().assertIsOff()
        screenshot("07-memory")
        compose.onNodeWithText("Back to settings").performScrollTo().performClick()
        compose.onNodeWithText("App guides").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Matches your installed version").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Matches your installed version").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Use with model").performScrollTo().assertIsNotEnabled()
        screenshot("11-app-guide")
        compose.onNodeWithText("Back to settings").performScrollTo().performClick()
        compose.onNodeWithText("Browser").performClick()
        compose.onNodeWithText("Website address").assertIsDisplayed()
        screenshot("06-browser")
        compose.onNodeWithText("Automations").performClick()
        compose.onNodeWithText("No scheduled checks").assertIsDisplayed()
        screenshot("08-automations")
        compose.onNodeWithText("Add check").performClick()
        compose.onNodeWithText("Daily time (HH:mm)").assertIsDisplayed()
        compose.onNodeWithText("Name").performTextInput("Synthetic check")
        compose.onNodeWithText("Website URL").performTextInput("https://example.com/status")
        compose.onNodeWithText("Save").assertIsEnabled()
        screenshot("09-schedule-editor")
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.onNodeWithText("No scheduled checks").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = File(requireNotNull(System.getProperty("buddy.capture.dir"))).apply { mkdirs() }
        // Draw the whole decor tree: Robolectric PixelCopy can omit unchanged RenderNodes.
        val bitmap = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        File(directory, "$name.png").outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val archive = File(directory, "render-$captureRun").apply { mkdirs() }
        File(directory, "$name.png").copyTo(File(archive, "$name.png"), overwrite = true)
        bitmap.recycle()
    }
}
