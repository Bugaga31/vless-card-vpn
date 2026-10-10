package com.vlesscardvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF4F8CFF)
val Good = Color(0xFF2EC27E)
val Bad = Color(0xFFE5484D)
val Warn = Color(0xFFF5A524)

@Composable
fun AppUi(onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val theme by remember { com.vlesscardvpn.core.Store.settings }.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // «Оформление»: classic dark, AMOLED (true black: saves battery on OLED screens), Material You (wallpaper colors, Android 12+)
    val scheme = when {
        theme.theme == "you" && android.os.Build.VERSION.SDK_INT >= 31 -> dynamicDarkColorScheme(ctx)
        theme.theme == "amoled" -> darkColorScheme(primary = Accent, background = Color.Black, surface = Color(0xFF0A0A0C),
            surfaceVariant = Color(0xFF16181D), onBackground = Color(0xFFE8EAED), onSurface = Color(0xFFE8EAED))
        else -> darkColorScheme(primary = Accent, background = Color(0xFF0F1115), surface = Color(0xFF171A21),
            surfaceVariant = Color(0xFF1F232C), onBackground = Color(0xFFE8EAED), onSurface = Color(0xFFE8EAED))
    }
    MaterialTheme(colorScheme = scheme) {
        var tab by remember { mutableIntStateOf(0) }
        Scaffold(
            containerColor = scheme.background,
            bottomBar = {
                NavigationBar(containerColor = scheme.surface) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.PowerSettingsNew, null) }, label = { Text("Главная") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.Dns, null) }, label = { Text("Серверы") })
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Filled.Psychology, null) }, label = { Text("Помощник") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Настройки") })
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(scheme.background)) {
                androidx.compose.animation.Crossfade(tab, label = "tab") { t -> when (t) {
                    0 -> HomeScreen(onConnect, onDisconnect, openServers = { tab = 1 })
                    1 -> ServersScreen()
                    3 -> ChatScreen()
                    else -> SettingsScreen()
                } }
            }
        }
    }
}
