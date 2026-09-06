package com.vlesscardvpn.ui.theme

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

// Private Lounge palette: obsidian surfaces with restrained champagne accents.
val MineralBackground = Color(0xFFF7F3EA)
val MineralSurface = Color(0xFFFFFFFF)
val MineralSurfaceSubtle = Color(0xFFEDE5D5)
val MineralSurfaceElevated = Color(0xFFFCFBF9)
val MineralBorder = Color(0xFFD8C9AA)
val MineralBorderActive = Color(0xFFB99A5D)
val InstrumentDarkBg = Color(0xFF0D0F14)
val InstrumentDarkSurface = Color(0xFF151821)
val InstrumentDarkSurfaceSubtle = Color(0xFF1D2230)
val InstrumentDarkBorder = Color(0xFF3B3529)
val GraphitePrimary = Color(0xFF1B1E1C)
val GraphiteSecondary = Color(0xFF555C57)
val GraphiteTertiary = Color(0xFF818A83)
val GraphitePrimaryDark = Color(0xFFF7F2E8)
val GraphiteSecondaryDark = Color(0xFFC9C1B2)
val GraphiteTertiaryDark = Color(0xFF8F887C)
val SignalOrange = Color(0xFFD6B36A)
val SignalOrangeHover = Color(0xFFC29A4F)
val SignalOrangeContainer = Color(0xFFF3E6C7)
val SignalOrangeContent = Color(0xFF30230F)
val SemanticGreen = Color(0xFF4FB887)
val SemanticGreenBg = Color(0xFF102B24)
val SemanticAmber = Color(0xFFD6A653)
val SemanticAmberBg = Color(0xFF302612)
val SemanticRed = Color(0xFFE06C75)
val SemanticRedBg = Color(0xFF32181D)
val SemanticNeutral = Color(0xFF9A958B)
val SemanticNeutralBg = Color(0xFF242730)

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
    primary = Color(0xFF8B6A2F),
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
    error = Color(0xFFB82828)
)

private val DarkColorScheme = darkColorScheme(
    primary = SignalOrange,
    onPrimary = Color(0xFF211A0D),
    primaryContainer = Color(0xFF332B1B),
    onPrimaryContainer = Color(0xFFF6E7C5),
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
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)

@Composable
fun VlessCardVpnTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = InstrumentTypography,
        content = content
    )
}
