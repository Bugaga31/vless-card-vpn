package com.vlesscardvpn.core

import com.vlesscardvpn.model.Subs

/** User-triggered discovery, not a background whole-catalog scan. Times are monotonic. */
object AutoDiscoveryPolicy {
    const val FAST_MS = 10_000L
    const val RECOVERY_MS = 45_000L
    const val SOURCE_MS = 3000L
    const val SOURCES = 4
    const val MAX_BYTES = 2 * 1024 * 1024
    const val REFRESH_COOLDOWN_MS = 60_000L
    fun sources(configured: List<String>, mobile: Boolean): List<String> = configured.distinct().sortedBy {
        when {
            it == Subs.CLOUD_WORKS -> 0
            mobile && Subs.isWhitelist(it) -> 1
            else -> 2
        }
    }.take(SOURCES)
    /** Never let the global 12h full-refresh timestamp suppress rescue of a dead catalog. */
    fun refreshDue(enabled: Boolean, now: Long, lastAttempt: Long?): Boolean = enabled &&
        (lastAttempt == null || now < lastAttempt || now - lastAttempt >= REFRESH_COOLDOWN_MS)
}
