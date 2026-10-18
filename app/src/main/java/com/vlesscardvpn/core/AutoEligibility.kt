package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** Labels are hints, not IP geolocation. Never silently fall back to a known Russian exit in Auto. */
object AutoEligibility {
    fun allowed(server: Server): Boolean = !server.name.contains("🇷🇺") && Countries.of(server.name) != "RU"
    const val TOP = 10
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
        val best = list.minOfOrNull { serviceRank(state(it),net,now) } ?: return emptyList()
        return list.filter { serviceRank(state(it),net,now) == best }
    }
}
