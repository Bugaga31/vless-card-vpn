package com.vlesscardvpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vlesscardvpn.BuildConfig
import com.vlesscardvpn.core.ExperiencePolicy
import com.vlesscardvpn.core.PhonePower
import com.vlesscardvpn.core.Store

/** Silent, finite ocean signal. No network gate, audio, fake loading percentage or infinite animation. */
@Composable fun LaunchIntro(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val settings by Store.settings.collectAsState()
    val power by PhonePower.state.collectAsState()
    val phase = remember { mutableFloatStateOf(0f) }
    val scale = remember { runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) }
    LaunchedEffect(owner, settings.oceanMotion, power.allowLight, scale) {
        if (!ExperiencePolicy.motion(settings.oceanMotion, true, power.allowLight, scale)) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (phase.floatValue < 1f) {
                kotlinx.coroutines.delay(50)
                phase.floatValue = (phase.floatValue + 1f / 24f).coerceAtMost(1f)
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(scheme.background), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Accent.copy(alpha = .08f), scheme.background),
                center = center, radius = size.maxDimension * .65f))
            for (i in 0..2) {
                val y = size.height * (.67f + i * .07f)
                drawArc(Accent.copy(alpha = .04f + i * .015f), 180f, 180f, false,
                    topLeft = androidx.compose.ui.geometry.Offset(-size.width * .3f, y),
                    size = androidx.compose.ui.geometry.Size(size.width * 1.6f, size.height * .4f), style = Stroke(2.dp.toPx()))
            }
        }
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), color = scheme.surfaceVariant) {
                Text("XRAY · ${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = scheme.primary, fontSize = 12.sp, letterSpacing = 2.sp)
            }
            Box(Modifier.size(156.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 0..2) {
                        val p = (phase.floatValue + i / 3f) % 1f
                        drawCircle(Accent.copy(alpha = (1f - p) * .45f), size.minDimension * (.28f + p * .21f), style = Stroke(1.dp.toPx()))
                    }
                }
                Box(Modifier.size(96.dp).background(scheme.surface, CircleShape)
                    .border(1.dp, Accent.copy(alpha = .5f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Accent, modifier = Modifier.size(42.dp))
                }
            }
            Text("Equalizer VPN", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground, textAlign = TextAlign.Center)
            Text("На одной волне", fontSize = 20.sp, color = scheme.primary, textAlign = TextAlign.Center)
            Text("Спокойный интерфейс.\nВаш маршрут к связи.", fontSize = 16.sp, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Открыть приложение") }
            Text("Без звука · можно сразу пропустить", fontSize = 12.sp, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
