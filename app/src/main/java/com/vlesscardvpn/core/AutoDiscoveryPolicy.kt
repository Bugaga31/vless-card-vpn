package com.vlesscardvpn.core

import com.vlesscardvpn.model.Subs

/** User-triggered discovery, not a background whole-catalog scan. Times are monotonic. */
object AutoDiscoveryPolicy {
    const val FAST_MS = 10_000L
    const val FIRST_CONNECT_MS = 10_000L
    const val RECOVERY_MS = 45_000L
    const val SOURCE_MS = 3000L
    const val SOURCES = 4
    const val RANKING_WINDOW = 256
    const val MAX_BYTES = 2 * 1024 * 1024
    const val REFRESH_COOLDOWN_MS = 60_000L
    const val FAILED_SOURCE_RETRY_MS = 20_000L
    fun sources(configured: List<String>, mobile: Boolean, round: Int = 0): List<String> {
        val unique = configured.distinct()
        val cloud = unique.filter { it == Subs.CLOUD_WORKS }
        val other = unique.filter { it != Subs.CLOUD_WORKS }.sortedBy { if (mobile && Subs.isWhitelist(it)) 0 else 1 }
        val offset = if (other.isEmpty()) 0 else ((round.coerceAtLeast(0).toLong() * (SOURCES - cloud.size)) % other.size).toInt()
        return (cloud + other.drop(offset) + other.take(offset)).take(SOURCES)
    }
    /** Never let the global 12h full-refresh timestamp suppress rescue of a dead catalog. */
    fun refreshDue(enabled: Boolean, now: Long, lastAttempt: Long?, failed: Boolean = false): Boolean {
        val cooldown = if (failed) FAILED_SOURCE_RETRY_MS else REFRESH_COOLDOWN_MS
        return enabled && (lastAttempt == null || now < lastAttempt || now - lastAttempt >= cooldown)
    }
}
