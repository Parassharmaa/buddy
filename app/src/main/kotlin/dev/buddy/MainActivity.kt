package dev.buddy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.buddy.connection.PhoneConnection
import dev.buddy.design.BuddyTheme
import dev.buddy.files.DocumentAccess
import dev.buddy.ui.BuddyApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BuddyApplication
        setContent {
            BuddyTheme {
                val model: BuddyViewModel =
                    viewModel { BuddyViewModel(app.store, PhoneConnection(app), DocumentAccess(app)) }
                BuddyApp(model)
            }
        }
    }
}
