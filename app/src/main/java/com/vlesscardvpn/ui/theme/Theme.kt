package com.vlesscardvpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Modern OLED Dark Theme Colors
val DarkBackground = Color(0xFF0F1117)
val DarkSurface = Color(0xFF181B26)
val DarkSurfaceVariant = Color(0xFF222638)
val NeonCyan = Color(0xFF00E5FF)
val NeonPurple = Color(0xFF8B5CF6)
val NeonGreen = Color(0xFF10B981)
val NeonAmber = Color(0xFFF59E0B)
val NeonRed = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)

private val DarkColors = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF0B192C),
    secondary = NeonPurple,
    onSecondary = Color.White,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = NeonRed
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    secondary = Color(0xFF7C3AED),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurface = Color(0xFF0F172A),
    onBackground = Color(0xFF0F172A)
)

@Composable
fun VlessCardVpnTheme(
    darkTheme: Boolean = true, // Dark theme default as requested
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content
    )
}
