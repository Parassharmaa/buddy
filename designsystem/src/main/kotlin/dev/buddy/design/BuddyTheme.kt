package dev.buddy.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF211267),
    background = Color(0xFFFAF8F5),
    surface = Color(0xFFFAF8F5),
    surfaceContainer = Color(0xFFF0EDE8),
    onSurface = Color(0xFF24232B)
)
private val Dark =
    darkColorScheme(primary = Color(0xFFC5BFFF), background = Color(0xFF17171D), surface = Color(0xFF17171D))

@Composable fun BuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
