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

// Legacy tokens used by the existing dark-only application screens.
// Keep foreground/background pairs together; new components use colorScheme.
val MineralBackground = Color(0xFF10151F)
val MineralSurface = Color(0xFF182131)
val MineralSurfaceSubtle = Color(0xFF233047)
val MineralSurfaceElevated = Color(0xFF293952)
val MineralBorder = Color(0xFF536680)
val MineralBorderActive = Color(0xFF8AB8FF)
val InstrumentDarkBg = MineralBackground
val InstrumentDarkSurface = MineralSurface
val InstrumentDarkSurfaceSubtle = MineralSurfaceSubtle
val InstrumentDarkBorder = MineralBorder
val GraphitePrimary = Color(0xFFF2F6FF)
val GraphiteSecondary = Color(0xFFC6D2E4)
val GraphiteTertiary = Color(0xFFA9B9D0)
val GraphitePrimaryDark = GraphitePrimary
val GraphiteSecondaryDark = GraphiteSecondary
val GraphiteTertiaryDark = GraphiteTertiary
val SignalOrange = Color(0xFF8AB8FF)
val SignalOrangeHover = Color(0xFF72A8F8)
val SignalOrangeContainer = Color(0xFF243F65)
val SignalOrangeContent = Color(0xFFD9E8FF)
val SemanticGreen = Color(0xFF79CDA4)
val SemanticGreenBg = Color(0xFF17382A)
val SemanticAmber = Color(0xFFE4BE79)
val SemanticAmberBg = Color(0xFF3A2F1C)
val SemanticRed = Color(0xFFF29498)
val SemanticRedBg = Color(0xFF3F2228)
val SemanticNeutral = GraphiteTertiary
val SemanticNeutralBg = MineralSurfaceSubtle

object InstrumentDimens {
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space20 = 20.dp
    val space24 = 24.dp
    val space32 = 32.dp
    val space48 = 48.dp
    val radiusSmall = 12.dp
    val radiusMedium = 20.dp
    val radiusLarge = 28.dp
    val radiusPill = 999.dp
    val minTouchTarget = 48.dp
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF73551F), onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E4C8), onPrimaryContainer = Color(0xFF30230F),
    background = Color(0xFFF7F3EA), onBackground = Color(0xFF1B211C),
    surface = Color.White, onSurface = Color(0xFF1B211C),
    surfaceVariant = Color(0xFFE9E7DD), onSurfaceVariant = Color(0xFF51594E),
    outline = Color(0xFF727A70), error = Color(0xFFAD2534)
)
private val DarkColorScheme = darkColorScheme(
    primary = SignalOrange, onPrimary = Color(0xFF0D2341),
    primaryContainer = SignalOrangeContainer, onPrimaryContainer = SignalOrangeContent,
    secondary = GraphiteSecondary, onSecondary = MineralBackground,
    background = MineralBackground, onBackground = GraphitePrimary,
    surface = MineralSurface, onSurface = GraphitePrimary,
    surfaceVariant = MineralSurfaceSubtle, onSurfaceVariant = GraphiteSecondary,
    outline = MineralBorder, error = SemanticRed, onError = Color(0xFF351016)
)
val InstrumentTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 42.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 1.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp)
)
@Composable
fun VlessCardVpnTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = InstrumentTypography, content = content)
}
