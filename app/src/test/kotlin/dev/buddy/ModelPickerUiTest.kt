package dev.buddy

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.buddy.design.BuddyTheme
import dev.buddy.ui.ModelPicker
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
class ModelPickerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun filterSelectsAnExactProviderIdAndKeepsManualEntryAvailable() {
        var selected: String? = null
        compose.setContent {
            BuddyTheme {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { open = true }) { Text("Load fixture models") }
                    if (open) ModelPicker(listOf("fixture-model", "日本語-model"), { selected = it }, { open = false })
                }
            }
        }
        compose.onNodeWithText("Load fixture models").performClick()
        compose.onNodeWithText("Filter model IDs").performTextInput("日本語")
        compose.onNodeWithText("fixture-model").assertDoesNotExist()
        compose.onNodeWithText("日本語-model").assertIsDisplayed()
        val directory = File(requireNotNull(System.getProperty("buddy.capture.dir"))).apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "21-model-picker.png").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        compose.onNodeWithText("日本語-model").performClick()
        assertEquals("日本語-model", selected)
    }
}
