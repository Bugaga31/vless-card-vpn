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
    private var dirty = true
    private var bytes = -1L
    private var pauseUntil = 0L    /** The phone recently lagged (game / low memory / heat): pause background work for a bit so it recovers fast. */
    private var lagUntil = 0L
    private var started = false
    private val events = android.os.Handler(android.os.Looper.getMainLooper())
    private var eventRefresh: Runnable? = null
    /** Process lifetime observer: immediate screen/thermal updates, coalesced battery broadcasts. */
    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        fun changed() {
            synchronized(this) { dirty = true }
            if (eventRefresh == null) eventRefresh = Runnable { runCatching { refresh(app) } }
            events.removeCallbacks(eventRefresh!!); events.post(eventRefresh!!)
        }
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF).apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) { changed() }
        }
        runCatching { app.registerReceiver(receiver, filter) }
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).addThermalStatusListener(
                PowerManager.OnThermalStatusChangedListener { changed() })
        }
        changed()
    }
    @Synchronized fun pause(minutes: Int) { pauseUntil = SystemClock.elapsedRealtime() + minutes.coerceIn(1, 60) * 60000L }
    @Synchronized fun resume() { pauseUntil = 0 }
    @Synchronized fun refresh(ctx: Context): PowerPolicy.Plan {
        val now = SystemClock.elapsedRealtime()
        if (dirty || now - sampledAt >= 10000) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = Net.underlying(ctx)?.let { cm.getNetworkCapabilities(it) }
            val memoryInfo = android.app.ActivityManager.MemoryInfo().also {
                runCatching { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
            }
            val total = TrafficStats.getTotalRxBytes().let { r -> TrafficStats.getTotalTxBytes().let { t -> if (r < 0 || t < 0) -1 else r + t } }
            val active = if (now - sampledAt in 0..999) sample.activeTraffic else
                bytes >= 0 && total >= bytes && now > sampledAt && (total - bytes).toDouble() * 1000 / (now - sampledAt) > 40000
            bytes = total
            sample = PowerPolicy.Input(now, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), bm.isCharging,
                pm.isPowerSaveMode, pm.isInteractive, Thermal.tempC(ctx), Thermal.status(ctx),
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
                android.os.Build.VERSION.SDK_INT >= 24 && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED, active, memoryInfo.lowMemory)
            sampledAt = now; dirty = false
            sensors.value = "Батарея: ${sample.battery.takeIf { it in 1..100 }?.let { "$it%" } ?: "неизвестно"} · ${if (sample.charging) "заряжается" else "без зарядки"}\nТемпература батареи / Android: ${Thermal.label(sample.temp, sample.thermal).ifEmpty { "нет данных" }}"
        }
        val s = Store.state.value.settings
        val gaming = GameSession.active()
        if (gaming || sample.lowMemory) lagUntil = now + 120000
        val plan = PowerPolicy.evaluate(sample.copy(now = now, gaming = gaming, recentLag = now < lagUntil), PowerPolicy.Options(com.vlesscardvpn.ai.LocalAutoControl.effectivePowerProfile(s.powerProfile), s.batteryThreshold,
            s.quietScreen, s.chargeOnly, s.wifiMaintenance, s.protectActiveTraffic, s.autoCool), memory, pauseUntil)
        memory = plan.memory
        Thermal.cooling = plan.memory.hot
        state.value = plan
        return plan
    }
}
