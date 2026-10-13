package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/** One shared sampler: no wakelock, no private data, no settings rewritten by thermal protection. */
object PhonePower {
    val state = MutableStateFlow(PowerPolicy.Plan())
    val sensors = MutableStateFlow("Датчики ещё не прочитаны")
    private var memory = PowerPolicy.Memory()
    private var sample = PowerPolicy.Input(0)
    private var sampledAt = -10000L
    private var bytes = -1L
    private var pauseUntil = 0L
    @Synchronized fun pause(minutes: Int) { pauseUntil = SystemClock.elapsedRealtime() + minutes.coerceIn(1, 60) * 60000L }
    @Synchronized fun resume() { pauseUntil = 0 }
    @Synchronized fun refresh(ctx: Context): PowerPolicy.Plan {
        val now = SystemClock.elapsedRealtime()
        if (now - sampledAt >= 10000) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.firstOrNull {
                !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            val total = TrafficStats.getTotalRxBytes().let { r -> TrafficStats.getTotalTxBytes().let { t -> if (r < 0 || t < 0) -1 else r + t } }
            val active = bytes >= 0 && total >= bytes && now > sampledAt && (total - bytes) * 1000 / (now - sampledAt) > 40000
            bytes = total
            sample = PowerPolicy.Input(now, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging,
                pm.isPowerSaveMode, pm.isInteractive, Thermal.tempC(ctx), Thermal.status(ctx),
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
                android.os.Build.VERSION.SDK_INT >= 24 && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED, active)
            sampledAt = now
            sensors.value = "Батарея: ${sample.battery.takeIf { it in 1..100 }?.let { "$it%" } ?: "неизвестно"} · ${if (sample.charging) "заряжается" else "без зарядки"}\nТемпература батареи / Android: ${Thermal.label(sample.temp, sample.thermal).ifEmpty { "нет данных" }}"
        }
        val s = Store.state.value.settings
        val plan = PowerPolicy.evaluate(sample.copy(now = now), PowerPolicy.Options(s.powerProfile, s.batteryThreshold,
            s.quietScreen, s.chargeOnly, s.wifiMaintenance, s.protectActiveTraffic, s.autoCool), memory, pauseUntil)
        memory = plan.memory
        Thermal.cooling = plan.memory.hot
        state.value = plan
        return plan
    }
}
