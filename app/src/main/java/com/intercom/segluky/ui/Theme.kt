package com.intercom.segluky.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The safety UI uses high-contrast fixed colours for the rider (red SOS,
// green OK, amber candidate). Until every screen is migrated to Material
// tokens, a forced light scheme is safer than unreadable dark cards.
private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    tertiary = Color(0xFF7D5260)
)

@Composable
fun InterSegTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
