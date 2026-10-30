package dev.buddy.fixture

import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FixtureStateDeviceTest {
    @Test fun unicodeDraftAndObservedResultSurviveRecreation() {
        val message = "こんにちは Buddy · café"
        ActivityScenario.launch(FixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.fixture_draft_input).setText(message)
                activity.findViewById<Button>(R.id.fixture_send_button).performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(message, activity.findViewById<EditText>(R.id.fixture_draft_input).text.toString())
                assertEquals(
                    "Sent to Alice: $message",
                    activity.findViewById<TextView>(R.id.fixture_result).text.toString()
                )
            }
        }
    }

    @Test fun protectedPageKeepsItsSecureFlagAndDoesNotRestorePasswordText() {
        ActivityScenario.launch(FixtureActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.fixture_protected_button).performClick()
                activity.findViewById<EditText>(R.id.fixture_password_input).setText("synthetic-fixture-only")
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertEquals("", activity.findViewById<EditText>(R.id.fixture_password_input).text.toString())
            }
        }
    }
}
