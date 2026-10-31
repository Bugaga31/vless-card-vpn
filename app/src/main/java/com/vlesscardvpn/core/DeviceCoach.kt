package com.vlesscardvpn.core

import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** Root-free advisor. It cannot kill other apps or attribute battery/CPU usage to them. */
object DeviceCoach {
    val state = MutableStateFlow<DeviceAdvice.Snapshot?>(null)
    val advice = MutableStateFlow<List<DeviceAdvice.Tip>>(emptyList())
    val phone = MutableStateFlow<PhoneInfo?>(null)
    private var bytes = -1L
    private var at = -1L
    private const val JOB = 13401
    private var inventoryAt = -1L
    private val automaticStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val visible = MutableStateFlow(false)
    private val automaticScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    /** Active while UI, VPN or explicit search is alive; the optional out-of-app Job remains opt-in. */
    fun startAutomatic(app: android.app.Application) {
        if (!automaticStarted.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private var count = 0
            override fun onActivityStarted(a: android.app.Activity) { count++; visible.value = count > 0 }
            override fun onActivityStopped(a: android.app.Activity) { count = (count - 1).coerceAtLeast(0); visible.value = count > 0 }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        automaticScope.launch {
            combine(visible, Tunnel.status.map { it.state == Tunnel.State.CONNECTED || it.state == Tunnel.State.CONNECTING }, Actions.running) {
                ui, vpn, search -> AutomaticCoachPolicy.active(ui, vpn, search)
            }.distinctUntilChanged().collectLatest { active ->
                if (active) while (true) {
                    runCatching { sample(app, includePhoneInfo = visible.value) }
                    PhonePower.requestRefresh(app)
                    delay(AutomaticCoachPolicy.SAMPLE_MS)
                }
            }
        }
    }
    @Synchronized fun sample(ctx: Context, includePhoneInfo: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (at >= 0 && now - at < 10000) return
        val mi = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val disk = StatFs(ctx.filesDir.path)
        val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
        val total = if (rx >= 0 && tx >= 0) rx + tx else -1L
        val active = at >= 0 && bytes >= 0 && total >= bytes && now > at && (total - bytes) * 1000 / (now - at) > 40000
        val signals = DeviceSignals(now, Thermal.tempC(ctx), Thermal.status(ctx), bm.isCharging,
            if (mi.totalMem > 0) 1.0 - mi.availMem.toDouble() / mi.totalMem else -1.0, mi.lowMemory, active)
        val snapshot = DeviceAdvice.Snapshot(signals, mi.availMem, mi.totalMem, disk.availableBytes,
            disk.totalBytes, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), pm.isPowerSaveMode)
        val predict = PhoneBrain.update(ctx, signals, Store.settings.value.phoneLearning, Actions.progress.value.running)
        state.value = snapshot; advice.value = DeviceAdvice.tips(snapshot, predict)
        if (AutomaticCoachPolicy.inventoryDue(includePhoneInfo, now, inventoryAt)) {
            phone.value = runCatching { PhoneInfo.collect(ctx) }.getOrNull()
            inventoryAt = now
        }
        at = now; bytes = total
    }
    fun backgroundEnabled(ctx: Context) = ctx.getSharedPreferences("phone_coach", 0).getBoolean("background", false)
    fun background(ctx: Context, enabled: Boolean): Boolean {
        val scheduler = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (!enabled) {
            scheduler.cancel(JOB); ctx.getSharedPreferences("phone_coach", 0).edit().putBoolean("background", false).apply(); return true
        }
        val ok = runCatching {
            val job = JobInfo.Builder(JOB, ComponentName(ctx, DeviceCoachJob::class.java)).setPeriodic(15 * 60000L)
            if (android.os.Build.VERSION.SDK_INT >= 26) job.setRequiresBatteryNotLow(true)
            scheduler.schedule(job.build()) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
        ctx.getSharedPreferences("phone_coach", 0).edit().putBoolean("background", ok).apply()
        return ok
    }
    fun restoreBackground(ctx: Context) {
        val scheduler = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (backgroundEnabled(ctx) && scheduler.getPendingJob(JOB) == null) background(ctx, true)
    }
    fun open(ctx: Context, action: String, packageName: String? = null): Boolean {
        val intent = when (action) {
            "network" -> Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
            "vpnBattery" -> if (android.os.Build.VERSION.SDK_INT >= 23 &&
                !(ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName))
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))
                else Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            "display" -> Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
            "storage" -> Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "battery" -> Intent("android.intent.action.POWER_USAGE_SUMMARY")
            "app" -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${packageName ?: ctx.packageName}"))
            else -> Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(intent); true }.getOrElse {
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }
    }
    data class AppUse(val pkg: String, val title: String, val minutes: Long)
    fun recentApps(ctx: Context): List<AppUse> {
        if (!AppWatch.hasAccess(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        return runCatching { um.queryAndAggregateUsageStats(now - 24 * 3600000L, now).values
            .filter { it.packageName != ctx.packageName && it.totalTimeInForeground >= 60000 }
            .sortedByDescending { it.totalTimeInForeground }.take(8).map { u ->
                val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(u.packageName, 0)).toString() }.getOrDefault(u.packageName)
                AppUse(u.packageName, label, u.totalTimeInForeground / 60000)
            } }.getOrDefault(emptyList())
    }
}
