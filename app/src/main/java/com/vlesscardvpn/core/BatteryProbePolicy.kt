package com.vlesscardvpn.core

/** Native multi-route observatory cadence; no wakelock and no disconnect on low battery. */
object BatteryProbePolicy {
    fun interval(profile: String) = when (profile) { "eco" -> "90s"; "fast" -> "20s"; else -> "45s" }
}
