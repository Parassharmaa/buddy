package dev.buddy

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.buddy.phone.FixtureScreenService
import dev.buddy.qualification.PhoneQualificationActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundFixtureDeviceTest {
    @Test(timeout = 60_000)
    fun foregroundProgramKeepsPhysicalTypingScopedAndSuppressesSecureContent() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)
        ).use { it.readNBytes(4096).toString(Charsets.UTF_8).trim() }
        val services = shell("settings get secure enabled_accessibility_services")
        val enabled = shell("settings get secure accessibility_enabled")
        require(services == "null" || services.matches(Regex("[A-Za-z0-9_.$/:]*")))
        require(enabled in setOf("null", "0", "1"))
        val component = "${context.packageName}/dev.buddy.phone.FixtureScreenService"
        val selected = services.takeUnless { it == "null" || it.isBlank() }?.let { "$it:$component" } ?: component
        var activity: PhoneQualificationActivity? = null
        try {
            shell("settings put secure enabled_accessibility_services $selected")
            shell("settings put secure accessibility_enabled 1")
            withTimeout(5_000) { while (!FixtureScreenService.connected) delay(50) }
            AuthenticatedHelperFixture.start(context, automation).use { bootstrap ->
                val opened = instrumentation.startActivitySync(
                    Intent(context, PhoneQualificationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ) as PhoneQualificationActivity
                activity = opened
                withTimeout(5_000) {
                    while (!withContext(Dispatchers.Main) { opened.hasWindowFocus() }) delay(50)
                }
                val started = CompletableDeferred<Unit>()
                val check = async(Dispatchers.Default) {
                    opened.runConnectedCheck(bootstrap.helper) { stage ->
                        if (stage == "Checking private Unicode edit 1 of 3") started.complete(Unit)
                    }
                }
                withTimeout(10_000) { started.await() }
                while (check.isActive) {
                    shell("input -d 0 text a")
                    delay(100)
                }
                assertTrue(check.await().concurrentTypingObserved)
                withContext(Dispatchers.Main) {
                    val text = opened.findViewById<EditText>(android.R.id.edit).text
                    assertTrue(text.isNotEmpty() && text.all { it == 'a' })
                }
            }
        } finally {
            withContext(Dispatchers.Main) { activity?.finish() }
            if (services == "null") {
                shell("settings delete secure enabled_accessibility_services")
            } else {
                shell("settings put secure enabled_accessibility_services $services")
            }
            if (enabled == "null") {
                shell("settings delete secure accessibility_enabled")
            } else {
                shell("settings put secure accessibility_enabled $enabled")
            }
        }
    }
}
