package com.vlesscardvpn.domain

/** "Только пинг": TCP reachability picks the server; HTTPS is checked only for display, never to reject. */
object ConnectCheckMode {
    const val PING = "ping"
    const val HTTPS = "https"
    fun normalize(value: String?): String = if (value == HTTPS) HTTPS else PING
    fun label(value: String?): String = if (normalize(value) == HTTPS) "Полная проверка HTTPS" else "Только пинг"
}

object AutoPingPolicy {
    /** Core start failures still move on to the next server; this is not an endless scan. */
    const val MAX_ATTEMPTS = 5

    /**
     * Reachable servers by best TCP ping (favorites first). Route: the user's own ByeDPI line if set,
     * otherwise a route previously proven on this network, otherwise the server's own parameters.
     */
    fun plan(
        measured: List<Pair<VlessConfig, Int>>,
        remembered: Map<String, String> = emptyMap(),
        customByeDpi: Boolean = false,
        limit: Int = MAX_ATTEMPTS
    ): List<AutoRouteAttempt> = measured
        .filter { (c, ping) -> ping > 0 && AutoConnectPolicy.supports(c) }
        .distinctBy { AutoConnectPolicy.identity(it.first) }
        .sortedWith(compareByDescending<Pair<VlessConfig, Int>> { it.first.isFavorite }.thenBy { it.second })
        .take(limit.coerceIn(0, MAX_ATTEMPTS))
        .map { (config, ping) -> route(config, ping, remembered[AutoConnectPolicy.identity(config)], customByeDpi) }

    fun route(config: VlessConfig, ping: Int, remembered: String?, customByeDpi: Boolean): AutoRouteAttempt {
        val allowed = AdaptiveRoutePolicy.profiles(config)
        if (customByeDpi && RouteProfile.BYEDPI in allowed) return AutoRouteAttempt(config, RouteProfile.BYEDPI, ping, null, custom = true)
        val profile = RouteProfile.entries.firstOrNull { it.name == remembered?.substringBefore('#') }
        return when {
            profile == null || profile !in allowed -> AutoRouteAttempt(config, RouteProfile.COMPATIBLE, ping)
            profile == RouteProfile.BYEDPI -> ByeDpiPreset.remembered(remembered)
                ?.let { AutoRouteAttempt(config, RouteProfile.BYEDPI, ping, it) } ?: AutoRouteAttempt(config, RouteProfile.COMPATIBLE, ping)
            remembered == profile.name -> AutoRouteAttempt(config, profile, ping)
            else -> AutoRouteAttempt(config, RouteProfile.COMPATIBLE, ping)
        }
    }
}
