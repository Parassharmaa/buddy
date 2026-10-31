package dev.buddy

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import dev.buddy.qualification.PhoneQualificationActivity
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
class PhoneQualificationUiTest {
    @get:Rule val compose = createAndroidComposeRule<PhoneQualificationActivity>()

    @Test fun engineeringPreflightRequiresFixtureAccessAndAConnectionPort() {
        compose.onNodeWithText("Run foreground check").performScrollTo().assertIsNotEnabled()
        val directory = File(requireNotNull(System.getProperty("buddy.capture.dir"))).apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "15-foreground-check.png").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        compose.onNodeWithText("Run model check with notification").performScrollTo().assertIsNotEnabled()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "16-supervised-check.png").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
        compose.onNodeWithText("Check takeover and return").performScrollTo().assertIsNotEnabled()
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "18-takeover-check.png").outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
    }
}
