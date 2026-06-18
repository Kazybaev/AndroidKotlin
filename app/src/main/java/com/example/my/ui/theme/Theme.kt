package com.example.my.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AuraMint,
    secondary = AuraCyan,
    background = AuraBackground,
    surface = AuraSurface,
    surfaceVariant = AuraSurfaceHigh,
    onPrimary = AuraBackground,
    onSecondary = AuraBackground,
    onBackground = AuraText,
    onSurface = AuraText,
    onSurfaceVariant = AuraTextMuted,
    error = AuraError
)

@Composable
fun MyTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
