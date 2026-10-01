package com.vlesscardvpn.domain

enum class RouteProfile(val label: String) {
    COMPATIBLE("Совместимый TLS"), FRAGMENT("TLS-фрагментация"), BYEDPI("VPN + ByeDPI")
}
object AdaptiveRoutePolicy {
    fun profiles(config: VlessConfig, remembered: String? = null): List<RouteProfile> {
        val allowed = if (AutoConnectPolicy.canFragment(config)) RouteProfile.entries.toList()
            else listOf(RouteProfile.COMPATIBLE)
        return allowed.sortedBy { if (it.name == remembered) 0 else 1 }
    }
    fun safeSettings(base: AppSettings, profile: RouteProfile) = AutoConnectPolicy.settings(base, profile == RouteProfile.FRAGMENT)
}
