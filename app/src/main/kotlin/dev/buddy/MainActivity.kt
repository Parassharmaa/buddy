package dev.buddy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.buddy.connection.PhoneConnection
import dev.buddy.design.BuddyTheme
import dev.buddy.files.DocumentAccess
import dev.buddy.ui.BuddyApp

class MainActivity : ComponentActivity() {
    private var automationRequests by mutableIntStateOf(0)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("buddy.destination") == "automations") automationRequests++
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("buddy.destination") == "automations") automationRequests++
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val app = application as BuddyApplication
        setContent {
            BuddyTheme {
                val model: BuddyViewModel =
                    viewModel { BuddyViewModel(app.store, PhoneConnection(app), DocumentAccess(app), app) }
                BuddyApp(model, automationRequests)
            }
        }
    }
}
