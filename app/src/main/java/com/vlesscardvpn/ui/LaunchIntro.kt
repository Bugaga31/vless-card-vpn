package com.vlesscardvpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vlesscardvpn.core.PhonePower
import com.vlesscardvpn.core.Store

/** Silent, skippable ripple: <=20 redraws/s for 1.2s, never a loop or a network startup gate. */
@Composable fun LaunchIntro(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val settings by Store.settings.collectAsState()
    val power by PhonePower.state.collectAsState()
    val phase = remember { mutableFloatStateOf(0f) }
    val scale = remember { runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) }
    LaunchedEffect(owner, settings.oceanMotion, power.allowLight, scale) {
        if (!settings.oceanMotion || !power.allowLight || scale <= 0f) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (phase.floatValue < 1f) {
                kotlinx.coroutines.delay(50)
                phase.floatValue = (phase.floatValue + 1f / 24f).coerceAtMost(1f)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .clickable(onClickLabel = "Пропустить заставку", onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).safeDrawingPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val t = phase.floatValue
                    for (i in 0..2) {
                        val p = (t + i / 3f) % 1f
                        drawCircle(Accent.copy(alpha = (1f - p) * .45f), size.minDimension * (.28f + p * .21f), style = Stroke(1.dp.toPx()))
                    }
                }
                Box(Modifier.size(100.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                    .border(1.dp, Accent.copy(alpha = .4f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Accent, modifier = Modifier.size(48.dp))
                }
            }
            Text("VLESS Card", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
            Text("На одной волне", style = MaterialTheme.typography.titleLarge, color = Accent, textAlign = TextAlign.Center)
            Text("Xray · ваш маршрут к связи", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Продолжить") }
        }
    }
}
