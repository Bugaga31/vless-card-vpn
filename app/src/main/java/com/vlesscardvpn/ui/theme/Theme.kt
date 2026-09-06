package com.vlesscardvpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Premium Cyber OLED Palette
val DarkBackground = Color(0xFF090A0F)
val DarkSurface = Color(0xFF12151F)
val DarkSurfaceVariant = Color(0xFF1B2030)
val DarkCardBg = Color(0xFF161A26)
val DarkBorder = Color(0xFF262D42)

val NeonCyan = Color(0xFF00F5FF)
val NeonCyanGlow = Color(0x3300F5FF)
val NeonPurple = Color(0xFF9D4EDD)
val NeonPurpleGlow = Color(0x339D4EDD)
val NeonGreen = Color(0xFF00FF9D)
val NeonGreenGlow = Color(0x3300FF9D)
val NeonAmber = Color(0xFFFFB703)
val NeonAmberGlow = Color(0x33FFB703)
val NeonRed = Color(0xFFFF3366)
val NeonRedGlow = Color(0x33FF3366)

val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF8E9BAE)
val TextTertiary = Color(0xFF5D6B82)

val CyberGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFF00F5FF), Color(0xFF9D4EDD))
)

val ActiveCardGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF1A233A), Color(0xFF141926))
)

val ConnectedGlowGradient = Brush.radialGradient(
    colors = listOf(NeonGreen.copy(alpha = 0.35f), Color.Transparent)
)

private val DarkColors = darkColorScheme(
    primary = NeonCyan,
    onPrimary = Color(0xFF060D17),
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

@Composable
fun VlessCardVpnTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
