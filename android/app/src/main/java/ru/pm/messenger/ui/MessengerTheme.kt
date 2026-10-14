package ru.pm.messenger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

@Composable
fun MessengerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tg.accent,
            onPrimary = Tg.text,
            background = Tg.bg,
            surface = Tg.bars,
            onBackground = Tg.text,
            onSurface = Tg.text,
            error = Tg.danger,
        ),
        content = content,
    )
}
