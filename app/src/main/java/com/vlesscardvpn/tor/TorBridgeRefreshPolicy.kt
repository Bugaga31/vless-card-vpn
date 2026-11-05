package com.vlesscardvpn.tor

/** Source cadence, not evidence that any bridge is reachable. Never depends on APK version. */
object TorBridgeRefreshPolicy {
    const val REFRESH_MS = 6 * 60 * 60_000L
    const val RETRY_MS = 15 * 60_000L
    fun due(enabled: Boolean, transport: String, now: Long, success: Long, attempted: Long): Boolean =
        enabled && transport in setOf("obfs4", "webtunnel") && now > 0 &&
            (success <= 0 || now < success || now - success >= REFRESH_MS) &&
            (attempted <= 0 || now < attempted || now - attempted >= RETRY_MS)

    /** A start that stalls on the saved bridges may fetch earlier, still only with consent and not in a loop. */
    const val STALLED_REFRESH_MS = 30 * 60_000L
    const val STALLED_RETRY_MS = 5 * 60_000L
    fun dueWhenStalled(enabled: Boolean, transport: String, now: Long, success: Long, attempted: Long): Boolean =
        enabled && transport in setOf("obfs4", "webtunnel") && now > 0 &&
            (success <= 0 || now < success || now - success >= STALLED_REFRESH_MS) &&
            (attempted <= 0 || now < attempted || now - attempted >= STALLED_RETRY_MS)

    fun replacement(catalog: TorBridgeCatalog.Parsed, transport: String, before: String,
                    currentTransport: String, currentLines: String, enabled: Boolean,
                    generation: Long, currentGeneration: Long, avoid: Set<String> = emptySet()): String? {
        if (!enabled || generation != currentGeneration || transport != currentTransport || before != currentLines) return null
        return catalog.forTransport(transport, avoid).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
