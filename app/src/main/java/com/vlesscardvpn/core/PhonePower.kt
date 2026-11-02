package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** One shared sampler: no wakelock, no private data, no settings rewritten by thermal protection. */
object PhonePower {
    val state = MutableStateFlow(PowerPolicy.Plan())
    val sensors = MutableStateFlow("Датчики ещё не прочитаны")
    private var memory = PowerPolicy.Memory()
    private var sample = PowerPolicy.Input(0)
    private var sampledAt = -10000L
    private val sensorInvalidations = java.util.concurrent.atomic.AtomicLong(1L)
    private var sampledGeneration = 0L
    private var bytes = -1L
    @Volatile private var pauseUntil = 0L
    private var lagUntil = 0L
    private var started = false
    private var modeListener: Any? = null
    private val events = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRequests = kotlinx.coroutines.channels.Channel<Context>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val sampler = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    init {
        sampler.launch {
            for (ctx in refreshRequests) {
                runCatching { refreshBlocking(ctx) }
            }
        }
    }
    /** UI reads the latest plan; binder/sensor work is serialized off the main thread. */
    fun requestRefresh(ctx: Context) { refreshRequests.trySend(ctx.applicationContext) }
    /** Only real system events invalidate cached sensors. UI reads must respect the shared cache.
     * Mark before enqueue: a conflated ordinary request must not erase an urgent invalidation. */
    private fun invalidateSensors(ctx: Context) {
        sensorInvalidations.incrementAndGet()
        requestRefresh(ctx)
    }
    /** Process lifetime observer: immediate screen/thermal updates, coalesced battery broadcasts. */
    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        fun changed() {
            invalidateSensors(app)
        }
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF).apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
            addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) {
                // A plug/unplug/plug cycle between minute samples is not a clean heat label.
                // No Store edits, tunnel restart, mask changes or probe-limit changes.
                if (i?.action == android.content.Intent.ACTION_POWER_CONNECTED ||
                    i?.action == android.content.Intent.ACTION_POWER_DISCONNECTED) PhoneBrain.invalidateWindow()
                changed()
            }
        }
        runCatching { app.registerReceiver(receiver, filter) }
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
            val audio = app.getSystemService(AudioManager::class.java)
            val listener = AudioManager.OnModeChangedListener { mode ->
                changed()
            }
            modeListener = listener
            audio?.addOnModeChangedListener(java.util.concurrent.Executor { command -> events.post(command) }, listener)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).addThermalStatusListener(
                PowerManager.OnThermalStatusChangedListener { changed() })
        }
        changed()
    }
    fun pause(minutes: Int) { pauseUntil = SystemClock.elapsedRealtime() + minutes.coerceIn(1, 60) * 60000L }
    fun resume() { pauseUntil = 0 }
    fun isVoiceCallActive(ctx: Context): Boolean = runCatching {
        VoiceCallPolicy.active((ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode)
    }.getOrDefault(false)
    fun refresh(ctx: Context): PowerPolicy.Plan {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            requestRefresh(ctx)
            return state.value
        }
        return refreshBlocking(ctx)
    }
    @Synchronized private fun refreshBlocking(ctx: Context): PowerPolicy.Plan {
        val now = SystemClock.elapsedRealtime()
        val voiceCall = isVoiceCallActive(ctx)
        val generation = sensorInvalidations.get()
        if (PhoneSamplingPolicy.due(now, sampledAt, generation != sampledGeneration || sample.voiceCall != voiceCall)) {
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
                android.os.Build.VERSION.SDK_INT >= 24 && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                active, memoryInfo.lowMemory, voiceCall = voiceCall)
            sampledAt = now; sampledGeneration = generation
            sensors.value = "Батарея: ${sample.battery.takeIf { it in 1..100 }?.let { "$it%" } ?: "неизвестно"} · ${if (sample.charging) "заряжается" else "без зарядки"}\nТемпература батареи / Android: ${Thermal.label(sample.temp, sample.thermal).ifEmpty { "нет данных" }}"
        }
        val s = Store.state.value.settings
        val gaming = GameSession.active()
        if (gaming || sample.lowMemory) lagUntil = now + 120000
        val plan = PowerPolicy.evaluate(sample.copy(now = now, gaming = gaming, recentLag = now < lagUntil, predictedPressure = s.phoneLearning && s.autoCool && PhoneBrain.reduceOptionalWork(now)), PowerPolicy.Options(s.powerProfile, s.batteryThreshold,
            s.quietScreen, s.chargeOnly, s.wifiMaintenance, s.protectActiveTraffic, s.autoCool), memory, pauseUntil)
        memory = plan.memory
        Thermal.cooling = plan.memory.hot
        state.value = plan
        return plan
    }
}
