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

/** Root-free advisor. It cannot kill other apps or attribute battery/CPU usage to them. */
object DeviceCoach {
    val state = MutableStateFlow<DeviceAdvice.Snapshot?>(null)
    val advice = MutableStateFlow<List<DeviceAdvice.Tip>>(emptyList())
    private var bytes = -1L
    private var at = -1L
    private const val JOB = 13401
    @Synchronized fun sample(ctx: Context) {
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
        val predict = PhoneBrain.update(ctx, signals, Store.settings.value.phoneLearning)
        state.value = snapshot; advice.value = DeviceAdvice.tips(snapshot, predict)
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
