package com.vlesscardvpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design Concept: PRECISION / FIELD INSTRUMENT
 * Palette: Mineral light stone background, warm surface, deep graphite typography,
 * Signal Orange for primary actions (<= 10%), Semantic Green/Amber/Red indicators.
 */

// Mineral Light Palette (Default)
val MineralBackground = Color(0xFFF5F4F0)      // Mineral chalk gray-beige
val MineralSurface = Color(0xFFFFFFFF)         // Pure clean white surface
val MineralSurfaceSubtle = Color(0xFFEBEAE4)   // Neutral stone container
val MineralSurfaceElevated = Color(0xFFFCFBF9) // Light surface overlay
val MineralBorder = Color(0xFFDEDCD5)          // Precision mechanical border
val MineralBorderActive = Color(0xFFB5B2A8)    // Border for focused/active components

// Dark Instrument Palette (Night / Field Low-Light mode)
val InstrumentDarkBg = Color(0xFF161817)
val InstrumentDarkSurface = Color(0xFF1E211F)
val InstrumentDarkSurfaceSubtle = Color(0xFF262A27)
val InstrumentDarkBorder = Color(0xFF333835)

// Typography Palette
val GraphitePrimary = Color(0xFF1B1E1C)        // Deep graphite (high contrast > 10:1)
val GraphiteSecondary = Color(0xFF555C57)      // Field label graphite (> 5:1)
val GraphiteTertiary = Color(0xFF818A83)       // Muted technical notes

val GraphitePrimaryDark = Color(0xFFECEEEB)
val GraphiteSecondaryDark = Color(0xFFA0A8A2)
val GraphiteTertiaryDark = Color(0xFF6E7570)

// Signal & Semantic Accents
val SignalOrange = Color(0xFFD95D0F)           // Industrial safety/control orange
val SignalOrangeHover = Color(0xFFC04E07)
val SignalOrangeContainer = Color(0xFFFFE8DC)
val SignalOrangeContent = Color(0xFF4A1A00)

val SemanticGreen = Color(0xFF1E7E46)          // Verified / Connected
val SemanticGreenBg = Color(0xFFE7F5EC)
val SemanticAmber = Color(0xFFB5740B)          // Degraded / Warning
val SemanticAmberBg = Color(0xFFFEF4E3)
val SemanticRed = Color(0xFFB82828)            // Error / Disconnected
val SemanticRedBg = Color(0xFFFBEAEA)
val SemanticNeutral = Color(0xFF707771)
val SemanticNeutralBg = Color(0xFFEDEFEA)

// Spacing & Radius Tokens
object InstrumentDimens {
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space20 = 20.dp
    val space24 = 24.dp
    val space32 = 32.dp
    val space48 = 48.dp

    val radiusSmall = 8.dp
    val radiusMedium = 12.dp
    val radiusLarge = 16.dp
    val radiusPill = 999.dp

    val minTouchTarget = 48.dp
}

private val LightColorScheme = lightColorScheme(
    primary = SignalOrange,
    onPrimary = Color.White,
    primaryContainer = SignalOrangeContainer,
    onPrimaryContainer = SignalOrangeContent,
    secondary = GraphiteSecondary,
    onSecondary = Color.White,
    background = MineralBackground,
    onBackground = GraphitePrimary,
    surface = MineralSurface,
    onSurface = GraphitePrimary,
    surfaceVariant = MineralSurfaceSubtle,
    onSurfaceVariant = GraphiteSecondary,
    outline = MineralBorder,
    error = SemanticRed
)

private val DarkColorScheme = darkColorScheme(
    primary = SignalOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4E2003),
    onPrimaryContainer = Color(0xFFFFDBC9),
    secondary = GraphiteSecondaryDark,
    onSecondary = Color(0xFF121413),
    background = InstrumentDarkBg,
    onBackground = GraphitePrimaryDark,
    surface = InstrumentDarkSurface,
    onSurface = GraphitePrimaryDark,
    surfaceVariant = InstrumentDarkSurfaceSubtle,
    onSurfaceVariant = GraphiteSecondaryDark,
    outline = InstrumentDarkBorder,
    error = SemanticRed
)

val InstrumentTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun VlessCardVpnTheme(
    darkTheme: Boolean = false, // Light mineral theme by default as per specification
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colors,
        typography = InstrumentTypography,
        content = content
    )
}
