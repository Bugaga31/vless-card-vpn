package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings

/** Reduces only this app's optional work. No pretend RAM cleaning or system CPU tuning. */
object DeviceEfficiency {
    fun apply(s: Settings): Settings = s.copy(powerProfile = "eco", oceanMotion = false,
        quietScreen = true, wifiMaintenance = true, protectActiveTraffic = true)
    /** User-selected presets affect our optional work only, not routing/security/system settings. */
    fun preset(s: Settings, name: String): Settings = when (name) {
        "eco" -> apply(s)
        "balanced" -> s.copy(powerProfile = "balanced")
        "fast" -> s.copy(powerProfile = "fast")
        else -> s
    }
    /** Undo only fields this action changed; keep newer subscriptions, routes and security preferences. */
    fun restore(current: Settings, previous: Settings): Settings = current.copy(
        powerProfile = previous.powerProfile, oceanMotion = previous.oceanMotion,
        quietScreen = previous.quietScreen, wifiMaintenance = previous.wifiMaintenance,
        protectActiveTraffic = previous.protectActiveTraffic)
}

