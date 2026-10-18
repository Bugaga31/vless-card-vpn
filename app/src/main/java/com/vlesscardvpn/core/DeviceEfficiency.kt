package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings

/** Reduces only this app's optional work. No pretend RAM cleaning or system CPU tuning. */
object DeviceEfficiency {
    fun apply(s: Settings): Settings = s.copy(powerProfile = "eco", oceanMotion = false,
        quietScreen = true, wifiMaintenance = true, protectActiveTraffic = true)
}
