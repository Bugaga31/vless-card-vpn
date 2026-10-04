package com.vlesscardvpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
    val radiusMedium = 16.dp
    val radiusLarge = 20.dp
    val radiusPill = 999.dp
    val minTouchTarget = 48.dp
}

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0066CC), onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F0FB), onPrimaryContainer = Color(0xFF143A67),
    secondary = Color(0xFF875104), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEED2), onSecondaryContainer = Color(0xFF65400A),
    tertiary = Color(0xFF18794E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5F3EA), onTertiaryContainer = Color(0xFF155638),
    background = Color(0xFFF5F5F7), onBackground = Color(0xFF202124),
    surface = Color.White, onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFEBEDF1), onSurfaceVariant = Color(0xFF59616E),
    outline = Color(0xFF818896), outlineVariant = Color(0xFFD8DCE3),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFFBEAE8), onErrorContainer = Color(0xFF7B1B16)
)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF86B6FF), onPrimary = Color(0xFF102C52),
    primaryContainer = Color(0xFF24374F), onPrimaryContainer = Color(0xFFCEE3FF),
    secondary = Color(0xFFEAC47F), onSecondary = Color(0xFF412E0A),
    secondaryContainer = Color(0xFF3A3021), onSecondaryContainer = Color(0xFFF1D6A5),
    tertiary = Color(0xFF70D6A4), onTertiary = Color(0xFF0F3B28),
    tertiaryContainer = Color(0xFF203A2D), onTertiaryContainer = Color(0xFFB8EBCD),
    background = Color(0xFF141518), onBackground = Color(0xFFF2F3F7),
    surface = Color(0xFF202126), onSurface = Color(0xFFF2F3F7),
    surfaceVariant = Color(0xFF2B2E35), onSurfaceVariant = Color(0xFFB4BAC7),
    outline = Color(0xFF7E8796), outlineVariant = Color(0xFF3B3F48),
    error = Color(0xFFFFB4AB), onError = Color(0xFF601410),
    errorContainer = Color(0xFF412824), onErrorContainer = Color(0xFFFFDAD5)
)
val InstrumentTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
)
@Composable
fun VlessCardVpnTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = androidx.compose.ui.platform.LocalView.current
    val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    androidx.compose.runtime.SideEffect {
        if (!view.isInEditMode && activity != null) {
            activity.window.statusBarColor = palette.background.toArgb()
            activity.window.navigationBarColor = palette.background.toArgb()
            androidx.core.view.WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = palette, typography = InstrumentTypography, content = content)
}
