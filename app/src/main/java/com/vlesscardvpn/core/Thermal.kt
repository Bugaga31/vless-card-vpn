package com.vlesscardvpn.core

import android.content.Context

/**
 * «Охлаждение»: the app can't cool the phone or close other apps (Android forbids it), but it can stop heating it
 * itself. When the phone is hot: background tests, mask evolution and speed checks pause; «Ускорение телефона»
 * (big packets = several times less CPU work per megabyte) turns on; tiny-packet masks are swapped for light ones.
 */
object Thermal {
    /** Battery temperature, °C (0 = unknown). */
    fun tempC(ctx: Context): Double = runCatching {
        val i = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        (i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
    }.getOrDefault(0.0)

    /** Android's thermal status 0 (none) … 6 (shutdown); -1 = unknown (Android < 10). */
    fun status(ctx: Context): Int = if (android.os.Build.VERSION.SDK_INT >= 29)
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).currentThermalStatus }.getOrDefault(-1) else -1

    /** Hot: Android says «moderate» or worse, or the battery is 41 °C+. Hysteresis: cool again below 38 °C and status ≤ light. */
    fun hot(temp: Double, status: Int, wasHot: Boolean): Boolean =
        if (wasHot) temp >= 38.0 || status >= 2 else temp >= 41.0 || status >= 2

    @Volatile var cooling = false

    fun label(temp: Double, status: Int): String = buildString {
        if (temp > 0) append("%.0f °C".format(temp))
        val s = when (status) { 0 -> "норма"; 1 -> "тёплый"; 2 -> "горячий"; 3 -> "сильно горячий"; 4, 5, 6 -> "перегрев"; else -> "" }
        if (s.isNotEmpty()) { if (isNotEmpty()) append(" · "); append(s) }
    }
}
