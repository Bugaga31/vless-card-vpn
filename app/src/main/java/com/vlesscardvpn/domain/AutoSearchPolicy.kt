package com.vlesscardvpn.domain

/** Bounded breadth-first search. TCP is a hint, never admission or success evidence. */
data class AutoRouteAttempt(val config: VlessConfig, val profile: RouteProfile, val tcpMs: Int) {
    val key: String get() = AutoConnectPolicy.identity(config) + ":" + profile.name
}
object AutoSearchPolicy {
    const val MAX_CANDIDATES = 48
    const val MAX_NODES_PER_POOL = 12
    const val SAVED_ATTEMPTS = 18
    const val DEADLINE_MS = 180_000L
    fun plan(
        candidates: List<Pair<VlessConfig, Int>>,
        remembered: Map<String, String> = emptyMap(),
        tried: Set<String> = emptySet()
    ): List<AutoRouteAttempt> {
        val ordered = candidates.filter { AutoConnectPolicy.supports(it.first) }
            .distinctBy { AutoConnectPolicy.identity(it.first) }
            .sortedWith(compareByDescending<Pair<VlessConfig, Int>> { it.first.isFavorite }
                .thenByDescending { !it.first.isFree }
                .thenBy { it.first.failureCount.coerceIn(0, 5) }
                .thenBy { if (it.second > 0) it.second else Int.MAX_VALUE })
            .filter { (config, _) ->
                AdaptiveRoutePolicy.profiles(config).any { profile ->
                    AutoConnectPolicy.identity(config) + ":" + profile.name !in tried
                }
            }
            .take(MAX_NODES_PER_POOL)
        val routes = ordered.map { (config, ping) ->
            val identity = AutoConnectPolicy.identity(config)
            val allowed = AdaptiveRoutePolicy.profiles(config)
            val defaultOrder = if (ping <= 0)
                listOf(RouteProfile.BYEDPI, RouteProfile.COMPATIBLE, RouteProfile.FRAGMENT)
            else listOf(RouteProfile.COMPATIBLE, RouteProfile.BYEDPI, RouteProfile.FRAGMENT)
            val prioritized = defaultOrder.filter { it in allowed }
                .sortedBy { if (it.name == remembered[identity]) 0 else 1 }
            prioritized.map { AutoRouteAttempt(config, it, ping) }
        }
        return (0..2).flatMap { round -> routes.mapNotNull { it.getOrNull(round) } }
            .filterNot { it.key in tried }
    }
    /** Use persisted IDs/metadata after import; parser-created IDs are not canonical. */
    fun importedRows(persisted: List<VlessConfig>, incoming: List<VlessConfig>): List<VlessConfig> {
        val identities = incoming.map(AutoConnectPolicy::identity).toSet()
        return persisted.filter { AutoConnectPolicy.identity(it) in identities }
            .distinctBy(AutoConnectPolicy::identity)
    }
    fun betterPartial(candidate: TunnelHealthReport, current: TunnelHealthReport?): Boolean {
        if (!candidate.internet || candidate.preferredServices) return false
        if (current == null) return true
        val score = { r: TunnelHealthReport -> (if (r.youtube) 1 else 0) + (if (r.telegram) 1 else 0) }
        return score(candidate) > score(current) || score(candidate) == score(current) &&
            candidate.latencyMs > 0 && (current.latencyMs <= 0 || candidate.latencyMs < current.latencyMs)
    }
}
