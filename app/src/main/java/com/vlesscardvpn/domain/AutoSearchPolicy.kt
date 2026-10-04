package com.vlesscardvpn.domain

/** Bounded breadth-first search. TCP is a hint, never admission or success evidence. */
data class AutoRouteAttempt(val config: VlessConfig, val profile: RouteProfile, val tcpMs: Int, val byeDpiPreset: ByeDpiPreset? = null,
                            /** The user's own ByeDPI command line from settings instead of a built-in preset. */
                            val custom: Boolean = false) {
    val key: String get() = AutoConnectPolicy.identity(config) + ":" + profile.name +
        if (profile == RouteProfile.BYEDPI) (if (custom) "#CUSTOM" else "#${(byeDpiPreset ?: ByeDpiPreset.COMBINED).name}") else ""
    val label: String get() = if (profile == RouteProfile.BYEDPI) (if (custom) CUSTOM_LABEL else (byeDpiPreset ?: ByeDpiPreset.COMBINED).label) else profile.label
    companion object { const val CUSTOM_LABEL = "ByeDPI · своя стратегия" }
}
object AutoSearchPolicy {
    const val MAX_CANDIDATES = 24
    const val MAX_NODES_PER_POOL = 12
    const val SAVED_ATTEMPTS = 6
    // An attempt may need a core start plus two 6 s HTTPS rounds; 60 s allowed only ~4 real attempts.
    const val DEADLINE_MS = 90_000L
    /** Re-opening the best partial route needs a core start plus one retried check (10 s was too short). */
    const val FALLBACK_MS = 25_000L
    private fun hasRememberedProfile(config: VlessConfig, remembered: Map<String, String>): Boolean {
        val value = remembered[AutoConnectPolicy.identity(config)] ?: return false
        val profile = RouteProfile.entries.firstOrNull { it.name == value.substringBefore('#') } ?: return false
        return profile in AdaptiveRoutePolicy.profiles(config) &&
            (if (profile == RouteProfile.BYEDPI) ByeDpiPreset.remembered(value) != null else value == profile.name)
    }
    /** Keep favorites first, then routes verified on this network, before the candidate cap. */
    fun candidates(configs: List<VlessConfig>, remembered: Map<String, String>, favoritesOnly: Boolean = false): List<VlessConfig> =
        AutoConnectPolicy.rank(configs, favoritesOnly)
            .sortedWith(compareByDescending<VlessConfig> { it.isFavorite }
                .thenByDescending { hasRememberedProfile(it, remembered) })
            .take(MAX_CANDIDATES)

    fun plan(
        candidates: List<Pair<VlessConfig, Int>>,
        remembered: Map<String, String> = emptyMap(),
        tried: Set<String> = emptySet(),
        attemptLimit: Int = AutoConnectPolicy.MAX_ATTEMPTS
    ): List<AutoRouteAttempt> {
        val budget = attemptLimit.coerceIn(0, AutoConnectPolicy.MAX_ATTEMPTS)
        if (budget == 0) return emptyList()
        // Reserve room for three compatible choices per node before importing more nodes.
        // Otherwise a six-attempt saved pool spends its entire quota on ordinary TLS.
        val nodeBudget = ((budget + 2) / 3).coerceAtMost(MAX_NODES_PER_POOL)
        val ordered = candidates.filter { AutoConnectPolicy.supports(it.first) }
            .distinctBy { AutoConnectPolicy.identity(it.first) }
            .sortedWith(compareByDescending<Pair<VlessConfig, Int>> { it.first.isFavorite }
                .thenByDescending { hasRememberedProfile(it.first, remembered) }
                .thenByDescending { !it.first.isFree }
                .thenBy { it.first.failureCount.coerceIn(0, 5) }
                .thenBy { if (it.second > 0) it.second else Int.MAX_VALUE })
            .filter { (config, ping) -> choices(config, ping, remembered[AutoConnectPolicy.identity(config)], 0).any { it.key !in tried } }
            .take(nodeBudget)
        // Spread the first ByeDPI variant across nodes; do not spend every attempt on one preset.
        val routes = ordered.mapIndexed { index, (config, ping) ->
            choices(config, ping, remembered[AutoConnectPolicy.identity(config)], index)
        }
        return (0 until (ByeDpiPreset.entries.size + 2)).flatMap { round -> routes.mapNotNull { it.getOrNull(round) } }
            .filterNot { it.key in tried }.take(budget)
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
            "BYEDPI" -> listOf(dpi[0], ordinary, fragment) + dpi.drop(1)
            "FRAGMENT" -> listOf(fragment, ordinary) + dpi
            "COMPATIBLE" -> listOf(ordinary, dpi[0], fragment) + dpi.drop(1)
            else -> if (ping <= 0) listOf(dpi[0], ordinary, fragment) + dpi.drop(1)
                else listOf(ordinary, dpi[0], fragment) + dpi.drop(1)
        }
    }
    /** Use persisted IDs/metadata after import; parser-created IDs are not canonical. */
    fun importedRows(persisted: List<VlessConfig>, incoming: List<VlessConfig>): List<VlessConfig> {
        val identities = incoming.map(AutoConnectPolicy::identity).toSet()
        return persisted.filter { AutoConnectPolicy.identity(it) in identities }
            .distinctBy(AutoConnectPolicy::identity)
    }
    fun betterPartial(candidate: TunnelHealthReport, current: TunnelHealthReport?): Boolean {
        if (!candidate.internet || candidate.usable) return false
        if (current == null) return true
        val score = { r: TunnelHealthReport -> (if (r.youtube) 1 else 0) + (if (r.telegram) 1 else 0) }
        return score(candidate) > score(current) || score(candidate) == score(current) &&
            candidate.latencyMs > 0 && (current.latencyMs <= 0 || candidate.latencyMs < current.latencyMs)
    }
}
