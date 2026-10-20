package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings

/** Compare only chain-affecting settings. UI, counters and learned weights never invalidate proof. */
object RouteContext {
    fun key(s: Settings): List<Any> = listOf(s.mode, s.balance, s.byeDpiArgs, s.byeDpiSni, s.dpiStrategy,
        s.dpiRemembered, s.hybridDomains, s.dnsUrl, s.ruDns, s.ruDirect, s.blockAds, s.blockStun, s.blockQuic,
        s.mux, s.services, s.alwaysVpn, s.alwaysDirect, s.smartYoutube, s.ytDpi, s.warpChain,
        s.myMasks, s.autoMasks, s.dpiEvoPool)
    fun invalidate(old: AppState, next: AppState): AppState {
        val strategyChanged = key(old.settings) != key(next.settings)
        val oldServers = if (old.servers !== next.servers) old.servers.associateBy { it.id } else emptyMap()
        if (!strategyChanged && old.servers === next.servers) return next
        val changed = next.servers.filter { strategyChanged || oldServers[it.id]?.let { previous ->
            // Name/source are provenance, not transport. All actual connection fields are compared.
            previous.copy(name = it.name, source = it.source, sources = it.sources) != it
        } == true }.mapTo(HashSet()) { it.id }
        if (changed.isEmpty()) return next
        return next.copy(states = next.states.mapValues { (id, state) ->
            if (id in changed) state.copy(serviceCheckedAt = 0, speedCheckedAt = 0, httpsCheckedAt = 0) else state
        })
    }
}
