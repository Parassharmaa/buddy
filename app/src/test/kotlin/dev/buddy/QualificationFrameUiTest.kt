package dev.buddy

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import dev.buddy.design.BuddyTheme
import dev.buddy.qualification.QualificationFrame
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QualificationFrameUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stopStaysVisibleAboveLongScrollableProgressAndRemainsActionable() {
        var stopped = false
        compose.setContent {
            BuddyTheme {
                QualificationFrame(active = true, stop = { stopped = true }) { insets ->
                    Column(
                        Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                            .verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Controlled fixture progress")
                        repeat(40) { Text("Recorded fixture step ${it + 1}") }
                    }
                }
            }
        }
        compose.onNodeWithText("Stop check").assertIsDisplayed()
        compose.onNodeWithText("Recorded fixture step 40").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop check").assertIsDisplayed().performClick()
        assertTrue(stopped)
        val directory = File(requireNotNull(System.getProperty("buddy.capture.dir"))).apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "19-persistent-stop-control.png").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
    }
}
