package com.vlesscardvpn.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vlesscardvpn.core.OceanPolicy
import com.vlesscardvpn.core.Thermal

/** Decorative sea. Phase state is read inside draw, so tabs/lists don't recompose every frame. */
@Composable
fun OceanBackground(motion: Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var eligible by remember { mutableStateOf(false) }
    val phase = remember { mutableStateOf(0.0) }
    val paths = remember { List(4) { Path() } }
    val colours = remember { listOf(Color(0xFF0B2536), Color(0xFF0B3042), Color(0xFF0C3948), Color(0xFF104252)) }
    val gradient = remember { Brush.verticalGradient(listOf(Color(0xFF081323), Color(0xFF10243A), Color(0xFF0A3440))) }
    DisposableEffect(ctx, owner, motion) {
        fun update() {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true
            val animScale = runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
            eligible = OceanPolicy.animate(motion, foreground, pm.isPowerSaveMode, percent, charging,
                Thermal.tempC(ctx), Thermal.status(ctx), animScale)
        }
        val observer = LifecycleEventObserver { _, _ -> foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); update() }
        owner.lifecycle.addObserver(observer)
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { update() } }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        runCatching { ctx.registerReceiver(receiver, filter) }
        val scaleObserver = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { update() } }
        runCatching { ctx.contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), false, scaleObserver) }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalListener = if (android.os.Build.VERSION.SDK_INT >= 29) PowerManager.OnThermalStatusChangedListener { update() } else null
        if (android.os.Build.VERSION.SDK_INT >= 29 && thermalListener != null) runCatching { pm.addThermalStatusListener(thermalListener) }
        update()
        onDispose {
            if (thermalListener != null && android.os.Build.VERSION.SDK_INT >= 29) runCatching { pm.removeThermalStatusListener(thermalListener) }
            owner.lifecycle.removeObserver(observer); runCatching { ctx.unregisterReceiver(receiver) }; runCatching { ctx.contentResolver.unregisterContentObserver(scaleObserver) } }
    }
    LaunchedEffect(eligible, foreground) {
        if (!eligible || !foreground) return@LaunchedEffect
        var previous = 0L
        while (true) {
            kotlinx.coroutines.delay(OceanPolicy.FRAME_MS)
            withFrameNanos { now ->
                if (previous == 0L) previous = now
                if (now - previous >= OceanPolicy.FRAME_MS * 1000000) {
                    phase.value = (phase.value + ((now - previous) / 1e9).coerceAtMost(.1)) % 720720.0
                    previous = now
                }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(gradient)
        val t = phase.value
        paths.forEachIndexed { layer, path ->
            path.reset(); path.moveTo(0f, size.height)
            val samples = 80
            for (i in 0..samples) {
                val x = i.toDouble() / samples
                path.lineTo((x * size.width).toFloat(), (OceanPolicy.y(x, t, layer) * size.height).toFloat())
            }
            path.lineTo(size.width, size.height); path.close()
            drawPath(path, colours[layer])
        }
    }
}
