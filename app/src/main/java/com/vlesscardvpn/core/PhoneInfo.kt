package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** «Помощник» knows the phone: model, Android, memory, battery, power saving, network — and what of it hurts the VPN. */
data class PhoneInfo(
    val model: String, val android: String, val sdk: Int, val cores: Int, val ramGb: Double, val ramFreeGb: Double, val storageFreeGb: Double,
    val battery: Int, val charging: Boolean, val powerSave: Boolean, val batteryOptimized: Boolean, val dataSaver: Boolean,
    val privateDns: String, val network: String, val metered: Boolean, val downMbps: Int, val upMbps: Int,
) {
    /** Problems that slow down or kill the VPN, with what to do. */
    fun problems(): List<String> = buildList {
        if (batteryOptimized) add("Оптимизация батареи для VLESS Card включена — система может усыплять VPN в фоне. Нажмите «Разрешить работу в фоне».")
        if (powerSave) add("Включён режим энергосбережения — он режет фоновую сеть и скорость.")
        if (dataSaver) add("Включена «Экономия трафика» — фоновые приложения работают медленнее.")
        if (privateDns == "hostname") add("Включён «Частный DNS» — часть запросов может идти мимо VPN; лучше «Автоматически» или выключить.")
        if (ramFreeGb in 0.01..0.4) add("Свободной памяти мало (${"%.1f".format(ramFreeGb)} ГБ) — система может выгружать VPN.")
        if (storageFreeGb in 0.01..1.0) add("Мало места (${"%.1f".format(storageFreeGb)} ГБ).")
        if (battery in 1..15 && !charging) add("Батарея ${battery}% — телефон может ограничивать фоновую работу.")
    }

    fun text(): String = "Телефон: $model, Android $android (API $sdk), $cores ядер, ОЗУ ${"%.1f".format(ramGb)} ГБ (свободно ${"%.1f".format(ramFreeGb)}), " +
        "свободно места ${"%.1f".format(storageFreeGb)} ГБ. Батарея $battery%" + (if (charging) " (заряжается)" else "") +
        (if (powerSave) ", энергосбережение ВКЛ" else "") + ". Оптимизация батареи для приложения: " + (if (batteryOptimized) "включена (плохо для VPN)" else "выключена ✓") +
        ". Сеть: $network" + (if (metered) " (лимитная)" else "") + (if (downMbps > 0) ", канал по оценке системы ↓$downMbps ↑$upMbps Мбит/с" else "") +
        ". Частный DNS: " + when (privateDns) { "hostname" -> "свой сервер"; "off" -> "выключен"; else -> "автоматически" } + (if (dataSaver) ". Экономия трафика ВКЛ" else "") + "."

    companion object {
        fun collect(c: Context): PhoneInfo {
            val am = c.getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo().also { runCatching { am.getMemoryInfo(it) } }
            val bm = c.getSystemService(BatteryManager::class.java)
            val pm = c.getSystemService(PowerManager::class.java)
            val cm = c.getSystemService(ConnectivityManager::class.java)
            // the real network under the VPN (the active one is the VPN itself while connected)
            val under = runCatching { cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.takeIf { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } }.firstOrNull() }.getOrNull()
            val stat = android.os.StatFs(c.filesDir.path)
            return PhoneInfo(
                model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", android = Build.VERSION.RELEASE ?: "?", sdk = Build.VERSION.SDK_INT,
                cores = Runtime.getRuntime().availableProcessors(), ramGb = mi.totalMem / 1e9, ramFreeGb = mi.availMem / 1e9,
                storageFreeGb = stat.availableBytes / 1e9,
                battery = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(0),
                charging = runCatching { bm.isCharging }.getOrDefault(false),
                powerSave = runCatching { pm.isPowerSaveMode }.getOrDefault(false),
                batteryOptimized = runCatching { !pm.isIgnoringBatteryOptimizations(c.packageName) }.getOrDefault(false),
                dataSaver = runCatching { cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED }.getOrDefault(false),
                privateDns = runCatching { android.provider.Settings.Global.getString(c.contentResolver, "private_dns_mode") }.getOrNull() ?: "opportunistic",
                network = Net.key(c), metered = runCatching { cm.isActiveNetworkMetered }.getOrDefault(false),
                downMbps = (under?.linkDownstreamBandwidthKbps ?: 0) / 1000, upMbps = (under?.linkUpstreamBandwidthKbps ?: 0) / 1000,
            )
        }
    }
}
