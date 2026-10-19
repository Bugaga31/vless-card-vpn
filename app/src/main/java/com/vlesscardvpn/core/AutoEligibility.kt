package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** Labels are hints, not IP geolocation. Never silently fall back to a known Russian exit in Auto. */
object AutoEligibility {
    fun allowed(server: Server): Boolean = !server.name.contains("🇷🇺") && Countries.of(server.name) != "RU"
    const val TOP = 8
    /** Every returned route is handed to Xray's multi-outbound pool, not held as a hidden spare. */
    fun connectedPool(servers: List<Server>, state: (Server) -> ServerState, net: String, now: Long, gaming: Boolean): List<Server> =
        servers.distinctBy { it.id }.filter { allowed(it) && state(it).works }.let { routes ->
            if (gaming) routes.sortedBy { ServerChoice.latencyScore(state(it).evidenceFor(net),state(it).realMs) }
            else routes.sortedWith(ServerChoice.downloadOrder(state,net,now))
        }.let(::independent).take(TOP)
    /** WireGuard endpoints of one account are alternatives, not independent channels. */
    fun independent(servers: List<Server>): List<Server> {
        val keys = HashSet<String>()
        return servers.filter { it.protocol != "wireguard" || keys.add(it.secret) }
    }
    fun clean(st: AppState): AppState {
        val keep = st.servers.filter(::allowed)
        if (keep.size == st.servers.size) return st
        val ids = keep.mapTo(HashSet()) { it.id }
        return st.copy(servers=keep, states=st.states.filterKeys { it in ids },
            settings=st.settings.copy(netServers=st.settings.netServers.mapValues { (_,v) -> v.filter { it in ids } }))
    }
    fun serviceRank(s: ServerState, net: String, now: Long): Int {
        if (s.serviceNetwork != net || s.serviceMask != s.maskFor(net) || s.serviceCheckedAt <= 0 ||
            now < s.serviceCheckedAt || now - s.serviceCheckedAt > 5 * 60000L) return 1
        if (s.ytOk == false || s.tgOk == false) return 2
        return if (s.ytOk == true && s.tgOk == true) 0 else 1
    }
    fun <T> servicePool(list: List<T>, state: (T) -> ServerState, net: String, now: Long): List<T> {
        // Unknown is not failed. One fully tested route must not discard seven HTTPS-working routes.
        // Explicit current-network failures stay out when any non-failed option exists.
        return list.filter { serviceRank(state(it),net,now) != 2 }.ifEmpty { list }
    }
}
