package dev.buddy.qualification

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun QualificationFrame(active: Boolean, stop: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            if (active) {
                Surface(shadowElevation = 2.dp) {
                    FilledTonalButton(
                        onClick = stop,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)
                    ) { Text("Stop check") }
                }
            }
        },
        content = content
    )
}
