package com.vlesscardvpn.watson

/** Optional local metadata work; never an authority over VPN or malware verdicts. */
object WatsonAuditPolicy {
    const val INTERVAL_MS = 15 * 60_000L
    const val RETRY_MS = 60_000L
    const val COALESCE_MS = 750L
    const val SCAN_BUDGET_MS = 15_000L
    fun attemptDue(now: Long, lastAttempt: Long, force: Boolean = false): Boolean =
        force || lastAttempt < 0 || now < lastAttempt || now - lastAttempt >= RETRY_MS
    fun publishable(startGeneration: Long, currentGeneration: Long) = startGeneration == currentGeneration
    fun allowed(enabled: Boolean, searching: Boolean, gaming: Boolean, allowLight: Boolean) =
        enabled && !searching && !gaming && allowLight
    fun due(now: Long, last: Long, generation: Long, completedGeneration: Long): Boolean =
        last < 0 || now < last || now - last >= INTERVAL_MS || generation != completedGeneration
}
