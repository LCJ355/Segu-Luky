package com.intercom.segluky.ui

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The safety UI keeps fixed high-contrast colours for the rider (red SOS,
// green OK, amber candidate), so the palette stays warm and neutral. Both
// schemes keep the orange SOS accents and the red/green status tones loud.
private val LightColors = lightColorScheme(
    primary = Color(0xFFB24A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBC4),
    onPrimaryContainer = Color(0xFF3A1400),
    secondary = Color(0xFF6B6259),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDE4DA),
    onSecondaryContainer = Color(0xFF221A12),
    tertiary = Color(0xFF146C2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF3E1),
    onTertiaryContainer = Color(0xFF07230F),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF7F3EE),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFCFAF7),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF0E9E1),
    onSurfaceVariant = Color(0xFF4E463C),
    outline = Color(0xFF857A6E),
    outlineVariant = Color(0xFFE0D7CC),
    inverseSurface = Color(0xFF34302C),
    inverseOnSurface = Color(0xFFF7EFE7)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF4A1D00),
    primaryContainer = Color(0xFF6A3000),
    onPrimaryContainer = Color(0xFFFFDBC4),
    secondary = Color(0xFFD0C5B8),
    onSecondary = Color(0xFF352E27),
    secondaryContainer = Color(0xFF4A423A),
    onSecondaryContainer = Color(0xFFEDE4DA),
    tertiary = Color(0xFF8FD7A2),
    onTertiary = Color(0xFF00391A),
    tertiaryContainer = Color(0xFF005227),
    onTertiaryContainer = Color(0xFFDDF3E1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF12100E),
    onBackground = Color(0xFFF1E9E1),
    surface = Color(0xFF1B1815),
    onSurface = Color(0xFFF1E9E1),
    surfaceVariant = Color(0xFF2A2520),
    onSurfaceVariant = Color(0xFFD0C5B8),
    outline = Color(0xFF9A8F82),
    outlineVariant = Color(0xFF4A423A),
    inverseSurface = Color(0xFFF1E9E1),
    inverseOnSurface = Color(0xFF1F1B16)
)

// Slightly tighter type than the Material defaults: the screens carry a lot of
// short status copy, so small sizes keep the cards scannable.
private val SeguTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.8.sp
    ),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.6.sp
    )
)

private val SeguShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

object ThemePreference {
    private const val PREFERENCES = "segu_theme"
    private const val DARK_THEME = "dark_theme"

    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(DARK_THEME, false)

    fun setDark(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DARK_THEME, enabled)
            .apply()
    }
}

@Composable
fun InterSegTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SeguTypography,
        shapes = SeguShapes,
        content = content
    )
}
