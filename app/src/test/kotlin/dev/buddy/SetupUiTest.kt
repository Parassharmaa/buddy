package dev.buddy

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.onNodeWithText("Set up Buddy").performClick()
        compose.onNodeWithText("Models").performClick()
        compose.onNodeWithText("Bring your own model").assertIsDisplayed()
        compose.onNodeWithText("Buddy").assertIsDisplayed()
        compose.onNodeWithText("Save and test model").assertIsDisplayed()
        screenshot("02-model-setup")
        compose.onNodeWithText("Back to settings").performClick()
        compose.onNodeWithText("Privacy").performClick()
        compose.onNodeWithText("Delete all local data").assertIsDisplayed()
        compose.onNodeWithText("Back to settings").assertIsDisplayed()
        screenshot("03-privacy")
        compose.onNodeWithText("Delete all local data").performClick()
        compose.onNodeWithText("Keep data").performClick()
        compose.onNodeWithText("Delete all local data").assertIsDisplayed()
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
