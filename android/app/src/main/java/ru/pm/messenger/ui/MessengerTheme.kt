package ru.pm.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1F6F5B)
private val GreenLight = Color(0xFF7DCEA0)

@Composable
fun MessengerTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = GreenLight, background = Color(0xFF121816), surface = Color(0xFF1C2420))
    } else {
        lightColorScheme(primary = Green, background = Color(0xFFF4F7F5), surface = Color(0xFFFFFFFF))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
