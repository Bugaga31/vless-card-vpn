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
        Box(Modifier.fillMaxSize()) {
        val sea = theme.oceanBackground && theme.theme != "amoled"
        if (sea) OceanBackground(theme.oceanMotion)
        Scaffold(
            containerColor = if (sea) Color.Transparent else scheme.background,
            bottomBar = {
                NavigationBar(containerColor = if (sea) scheme.surface.copy(alpha = .95f) else scheme.surface) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.PowerSettingsNew, null) }, label = { Text("Главная") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.Dns, null) }, label = { Text("Серверы") })
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Filled.Psychology, null) }, label = { Text("Помощник") })
                    NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Icon(Icons.Filled.PhoneAndroid, null) }, label = { Text("Телефон") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Настройки") })
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
        }
    }
}
