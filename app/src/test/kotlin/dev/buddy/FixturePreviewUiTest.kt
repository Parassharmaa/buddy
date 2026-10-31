package dev.buddy

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import dev.buddy.core.Bounds
import dev.buddy.core.Observation
import dev.buddy.core.UiElement
import dev.buddy.design.BuddyTheme
import dev.buddy.qualification.FixturePreview
import dev.buddy.qualification.FixturePreviewCard
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
class FixturePreviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewShowsItsAgeAndHidesProtectedText() {
        val now = SystemClock.elapsedRealtime()
        val observation = Observation(
            "id", "run", 1, now, "dev.buddy.fixture", 720, 1280,
            listOf(UiElement("edit", "नमस्ते · こんにちは · café · 😀", "dev.buddy.fixture", Bounds(0, 0, 100, 50))), "fp"
        )
        compose.setContent {
            BuddyTheme {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FixturePreviewCard(FixturePreview.from(observation))
                    FixturePreviewCard(
                        FixturePreview.from(
                            observation.copy(
                                protected = true,
                                nodes = observation.nodes.map { it.copy(label = "private-preview-canary") }
                            )
                        )
                    )
                    FixturePreviewCard(
                        FixturePreview.from(
                            observation.copy(
                                capturedAtElapsedMs = now - 3_000,
                                nodes = observation.nodes.map { it.copy(label = "Earlier fixture observation") }
                            )
                        )
                    )
                }
            }
        }
        compose.onNodeWithText("Recent filtered text excerpt").assertIsDisplayed()
        compose.onNodeWithText("Stale filtered text excerpt").assertIsDisplayed()
        compose.onNodeWithText("Private-app preview hidden").assertIsDisplayed()
        compose.onNodeWithText("private-preview-canary").assertDoesNotExist()
        val directory = File(requireNotNull(System.getProperty("buddy.capture.dir"))).apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "17-filtered-fixture-preview.png").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
    }
}
