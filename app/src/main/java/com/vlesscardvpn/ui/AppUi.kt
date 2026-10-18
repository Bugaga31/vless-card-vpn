package com.vlesscardvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF78DEEA)
val Good = Color(0xFF7AE4BA)
val Bad = Color(0xFFFFA4AC)
val Warn = Color(0xFFFFCE83)

@Composable
fun AppUi(onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val theme by remember { com.vlesscardvpn.core.Store.settings }.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // «Оформление»: classic dark, AMOLED (true black: saves battery on OLED screens), Material You (wallpaper colors, Android 12+)
    val scheme = when {
        theme.theme == "you" && android.os.Build.VERSION.SDK_INT >= 31 -> dynamicDarkColorScheme(ctx)
        theme.theme == "amoled" -> darkColorScheme(primary = Accent, onPrimary = Color(0xFF05212B), secondary = Color(0xFFB6AFF6),
            onSurfaceVariant = Color(0xFFB3C6D5), outline = Color(0xFF536D80), background = Color.Black, surface = Color(0xFF0A0A0C),
            surfaceVariant = Color(0xFF16181D), onBackground = Color(0xFFF0F6FC), onSurface = Color(0xFFF0F6FC))
        else -> darkColorScheme(primary = Accent, onPrimary = Color(0xFF05212B), secondary = Color(0xFFB6AFF6),
            onSurfaceVariant = Color(0xFFB3C6D5), outline = Color(0xFF536D80), background = Color(0xFF07131F), surface = Color(0xFF102536),
            surfaceVariant = Color(0xFF183345), onBackground = Color(0xFFF0F6FC), onSurface = Color(0xFFF0F6FC))
    }
    MaterialTheme(colorScheme = scheme) {
        var tab by remember { mutableIntStateOf(0) }
        var intro by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(theme.welcomeSound && com.vlesscardvpn.core.Tunnel.socks == null) }
        LaunchedEffect(intro) { if (intro) { withFrameNanos { }; withFrameNanos { }; android.util.Log.i("VCIntro", "INTRO_VISIBLE"); kotlinx.coroutines.delay(1400); intro = false } }
        Box(Modifier.fillMaxSize()) {
        val sea = theme.oceanBackground && theme.theme != "amoled"
        if (sea) OceanBackground(theme.oceanMotion)
        Scaffold(
            containerColor = if (sea) Color.Transparent else scheme.background,
            bottomBar = {
                // Keep five accessible targets without splitting Russian words on narrow screens.
                val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.2f
                val tabs = listOf(
                    Triple(0, "Главная", Icons.Filled.PowerSettingsNew),
                    Triple(1, "Серверы", Icons.Filled.Dns),
                    Triple(3, "Помощник", Icons.Filled.Psychology),
                    Triple(4, "Телефон", Icons.Filled.PhoneAndroid),
                    Triple(2, "Настройки", Icons.Filled.Settings)
                )
                NavigationBar(containerColor = if (sea) scheme.surface.copy(alpha = .95f) else scheme.surface) {
                    tabs.forEach { (index, title, glyph) ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(glyph, contentDescription = title) },
                            alwaysShowLabel = !compact,
                            label = { Text(title, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                androidx.compose.animation.Crossfade(tab, label = "tab") { t -> when (t) {
                    0 -> HomeScreen(onConnect, onDisconnect, openServers = { tab = 1 })
                    1 -> ServersScreen()
                    3 -> ChatScreen()
                    4 -> DeviceScreen()
                    else -> SettingsScreen()
                } }
            }
        }
        if (intro) LaunchIntro(onDismiss = { intro = false })
        }
    }
}
