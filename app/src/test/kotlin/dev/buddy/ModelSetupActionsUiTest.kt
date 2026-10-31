package dev.buddy

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.buddy.design.BuddyTheme
import dev.buddy.ui.ModelSetupActions
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
class ModelSetupActionsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelIsAvailableForNetworkWaitsAndAbsentDuringCredentialCommit() {
        val phase = mutableStateOf(0)
        var cancellations = 0
        compose.setContent {
            BuddyTheme {
                Column(Modifier.padding(20.dp)) {
                    ModelSetupActions(
                        testing = phase.value != 2,
                        saving = phase.value == 1,
                        loading = phase.value == 2,
                        canSave = true,
                        onTest = {},
                        onSaveUnverified = {},
                        onCancel = { cancellations++ }
                    )
                }
            }
        }
        compose.onNodeWithText("Cancel request").assertIsDisplayed().performClick()
        compose.onNodeWithText("Save anyway · Unverified").assertIsNotEnabled()
        assertEquals(1, cancellations)
        val directory = File(requireNotNull(System.getProperty("buddy.capture.dir"))).apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "22-cancel-model-check.png").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        compose.runOnIdle { phase.value = 1 }
        compose.onNodeWithText("Saving model").assertIsDisplayed()
        compose.onNodeWithText("Cancel request").assertDoesNotExist()
        compose.runOnIdle { phase.value = 2 }
        compose.onNodeWithText("Cancel request").performClick()
        assertEquals(2, cancellations)
    }
}
