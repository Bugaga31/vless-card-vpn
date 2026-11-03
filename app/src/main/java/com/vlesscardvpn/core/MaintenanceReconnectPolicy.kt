package com.vlesscardvpn.core

/** Optional improvements only. Not used to delay emergency healing or the Fast Auto deadline. */
object MaintenanceReconnectPolicy {
    const val STABLE_MS = 30_000L
    fun remaining(now: Long, connectedAt: Long): Long =
        if (now < connectedAt) STABLE_MS else (STABLE_MS - (now - connectedAt)).coerceAtLeast(0L)
    /** Heavy optional probes share the startup protection of optional reconnects. */
    fun mayProbe(now: Long, connectedAt: Long, allowed: Boolean): Boolean =
        allowed && remaining(now, connectedAt) == 0L
    fun idle(rxBefore: Long, txBefore: Long, rxAfter: Long, txAfter: Long): Boolean =
        rxBefore >= 0 && txBefore >= 0 && rxAfter == rxBefore && txAfter == txBefore
    fun mayApply(current: Boolean, stable: Boolean, protectActiveTraffic: Boolean, idle: Boolean): Boolean =
        current && stable && (!protectActiveTraffic || idle)
}
