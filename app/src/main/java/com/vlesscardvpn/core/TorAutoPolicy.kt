package com.vlesscardvpn.core

/** A bounded service round may enable Tor, but cannot prove that Tor works. */
object TorAutoPolicy {
    fun shouldEnable(enabled: Boolean, alreadyRouting: Boolean, serviceSelected: Boolean,
                     expectedRoutes: Set<String>, measurements: Map<String, Int>): Boolean =
        enabled && !alreadyRouting && serviceSelected && expectedRoutes.isNotEmpty() &&
            measurements.keys == expectedRoutes && measurements.values.all { it == 0 }

    fun needsReconnect(torEnabledNow: Boolean, pinsChanged: Boolean, selectedCount: Int): Boolean =
        torEnabledNow || (pinsChanged && selectedCount > 1)
}
