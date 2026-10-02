package com.vlesscardvpn.domain

/** Bounded breadth-first search. TCP is a hint, never admission or success evidence. */
data class AutoRouteAttempt(val config: VlessConfig, val profile: RouteProfile, val tcpMs: Int, val byeDpiPreset: ByeDpiPreset? = null) {
    val key: String get() = AutoConnectPolicy.identity(config) + ":" + profile.name +
        if (profile == RouteProfile.BYEDPI) "#${(byeDpiPreset ?: ByeDpiPreset.COMBINED).name}" else ""
    val label: String get() = if (profile == RouteProfile.BYEDPI) (byeDpiPreset ?: ByeDpiPreset.COMBINED).label else profile.label
}
object AutoSearchPolicy {
    const val MAX_CANDIDATES = 24
    const val MAX_NODES_PER_POOL = 12
    const val SAVED_ATTEMPTS = 6
    const val DEADLINE_MS = 60_000L
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
            .filter { (config, ping) -> choices(config, ping, remembered[AutoConnectPolicy.identity(config)], 0).any { it.key !in tried } }
            .take(MAX_NODES_PER_POOL)
        // Spread the first ByeDPI variant across nodes; do not spend every attempt on one preset.
        val routes = ordered.mapIndexed { index, (config, ping) ->
            choices(config, ping, remembered[AutoConnectPolicy.identity(config)], index)
        }
        return (0 until 5).flatMap { round -> routes.mapNotNull { it.getOrNull(round) } }
            .filterNot { it.key in tried }.take(AutoConnectPolicy.MAX_ATTEMPTS)
    }
    private fun choices(config: VlessConfig, ping: Int, remembered: String?, index: Int): List<AutoRouteAttempt> {
        val allowed = AdaptiveRoutePolicy.profiles(config)
        val presets = ByeDpiPreset.order(config.failureCount.coerceAtLeast(0) + index, remembered)
        val ordinary = AutoRouteAttempt(config, RouteProfile.COMPATIBLE, ping)
        val fragment = AutoRouteAttempt(config, RouteProfile.FRAGMENT, ping)
        val dpi = presets.map { AutoRouteAttempt(config, RouteProfile.BYEDPI, ping, it) }
        if (RouteProfile.BYEDPI !in allowed) return listOf(ordinary)
        val rememberedProfile = remembered?.substringBefore('#')
        return when (rememberedProfile) {
            "BYEDPI" -> listOf(dpi[0], ordinary, fragment, dpi[1], dpi[2])
            "FRAGMENT" -> listOf(fragment, ordinary, dpi[0], dpi[1], dpi[2])
            "COMPATIBLE" -> listOf(ordinary, dpi[0], fragment, dpi[1], dpi[2])
            else -> if (ping <= 0) listOf(dpi[0], ordinary, fragment, dpi[1], dpi[2])
                else listOf(ordinary, dpi[0], fragment, dpi[1], dpi[2])
        }
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
