package com.vlesscardvpn.watson

/** Optional local metadata work; never an authority over VPN or malware verdicts. */
object WatsonAuditPolicy {
    const val INTERVAL_MS = 15 * 60_000L
    fun allowed(enabled: Boolean, searching: Boolean, gaming: Boolean, allowLight: Boolean) =
        enabled && !searching && !gaming && allowLight
    fun due(now: Long, last: Long, generation: Long, completedGeneration: Long): Boolean =
        last < 0 || now < last || now - last >= INTERVAL_MS || generation != completedGeneration
}
