package com.vlesscardvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign

private val DarkAccent = Color(0xFF8FC1FF)
val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
val Good: Color @Composable get() = if(MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFF17643E) else Color(0xFF7AE4BA)
val Bad: Color @Composable get() = if(MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFFAD2334) else Color(0xFFFFA4AC)
val Warn: Color @Composable get() = if(MaterialTheme.colorScheme.surface.luminance() > .5f) Color(0xFF805000) else Color(0xFFFFCE83)

@Composable
fun AppUi(onConnect: () -> Unit, onDisconnect: () -> Unit, onConnectFound:()->Unit = onConnect) {
    val theme by remember { com.vlesscardvpn.core.Store.settings }.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val light = theme.theme == "light" || (theme.theme == "system" && !systemDark)
    val activity = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull() }
    DisposableEffect(activity,theme.secureScreen) {
        val window = activity?.window
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        val hadFlag = ((window?.attributes?.flags ?: 0) and flag) != 0
        if(theme.secureScreen) window?.addFlags(flag) else window?.clearFlags(flag)
        onDispose { if(hadFlag) window?.addFlags(flag) else window?.clearFlags(flag) }
    }
    val scheme = when {
        light -> lightColorScheme(primary=Color(0xFF125BB2),onPrimary=Color.White,
            secondary=Color(0xFF585181),background=Color(0xFFF3F6FB),surface=Color.White,
            surfaceVariant=Color(0xFFE7EDF5),onSurface=Color(0xFF172334),onBackground=Color(0xFF172334),
            onSurfaceVariant=Color(0xFF40536A),outline=Color(0xFF60748A))
        theme.theme == "you" && android.os.Build.VERSION.SDK_INT >= 31 -> dynamicDarkColorScheme(ctx)
        theme.theme == "amoled" -> darkColorScheme(primary = DarkAccent, onPrimary = Color(0xFF092D52), secondary = Color(0xFFB6AFF6),
            onSurfaceVariant = Color(0xFFB3C6D5), outline = Color(0xFF536D80), background = Color.Black, surface = Color(0xFF0A0A0C),
            surfaceVariant = Color(0xFF16181D), onBackground = Color(0xFFF0F6FC), onSurface = Color(0xFFF0F6FC))
        else -> darkColorScheme(primary = DarkAccent, onPrimary = Color(0xFF092D52), secondary = Color(0xFFB6AFF6),
            onSurfaceVariant = Color(0xFFB3C6D5), outline = Color(0xFF536D80), background = Color(0xFF10151C), surface = Color(0xFF1A222E),
            surfaceVariant = Color(0xFF263345), onBackground = Color(0xFFF0F6FC), onSurface = Color(0xFFF0F6FC))
    }
    val readable=Typography().let {t->t.copy(
        bodySmall=t.bodySmall.copy(fontSize=14.sp,lineHeight=20.sp),
        labelSmall=t.labelSmall.copy(fontSize=12.sp,lineHeight=16.sp),
        labelMedium=t.labelMedium.copy(fontSize=14.sp,lineHeight=20.sp))}
    MaterialTheme(colorScheme = scheme, typography=readable) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var intro by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(theme.welcomeSound && !theme.reducedMotion && !light && com.vlesscardvpn.core.Tunnel.socks == null) }
        LaunchedEffect(intro) { if (intro) { withFrameNanos { }; withFrameNanos { }; android.util.Log.i("VCIntro", "INTRO_VISIBLE"); kotlinx.coroutines.delay(1400); intro = false } }
        Box(Modifier.fillMaxSize()) {
        val sea = theme.oceanBackground && theme.theme != "amoled" && !light
        if (sea) OceanBackground(theme.oceanMotion && !theme.reducedMotion)
        Scaffold(
            containerColor = if (sea) Color.Transparent else scheme.background,
            // Transparent ocean is not a ColorScheme surface: do not inherit black.
            contentColor = scheme.onBackground,
            bottomBar = {
                // Keep five accessible targets without splitting Russian words on narrow screens.
                val compact = LocalConfiguration.current.screenWidthDp < 360 || LocalDensity.current.fontScale > 1.2f
                val tabs = listOf(
                    Triple(0, "Главная", Icons.Filled.PowerSettingsNew),
                    Triple(1, "Серверы", Icons.Filled.Dns),
                    Triple(3, "Помощник", Icons.Filled.Psychology),
                    Triple(4, "ВАТСОН", Icons.Filled.PhoneAndroid),
                    Triple(2, "Настройки", Icons.Filled.Settings)
                )
                Column(Modifier.background(scheme.surface.copy(alpha = .97f))) {
                NavigationBar(containerColor = Color.Transparent) {
                    tabs.forEach { (index, title, glyph) ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(glyph, contentDescription = title) },
                            alwaysShowLabel = !compact,
                            label = if (compact) null else { { Text(title, fontSize = 12.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) } })
                    }
                }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                androidx.compose.animation.Crossfade(tab, animationSpec = tween(if(theme.reducedMotion) 0 else 200), label = "tab") { t -> when (t) {
                    0 -> HomeScreen(onConnect, onDisconnect, openServers = { tab = 1 }, onConnectFound=onConnectFound, openWatson={tab=4})
                    1 -> ServersScreen(onConnectFound)
                    3 -> ChatScreen()
                    4 -> WatsonScreen(openPhone={tab=5})
                    5 -> DeviceScreen(openWatson={tab=4})
                    else -> SettingsScreen()
                } }
            }
        }
        if (intro) LaunchIntro(onDismiss = { intro = false })
        }
    }
}
