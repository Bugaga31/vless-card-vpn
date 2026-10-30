package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** Labels are hints, not IP geolocation. Never silently fall back to a known Russian exit in Auto. */
object AutoEligibility {
    fun allowed(server: Server): Boolean = ServerOnlyPolicy.userRoute(server) && !server.name.contains("🇷🇺") && Countries.of(server.name) != "RU"
    fun vlessServer(server: Server): Boolean = allowed(server) && !com.vlesscardvpn.xray.Masks.isWarp(server)
    const val TOP = 8
    const val ACTIVE = 1
    /** Only the chosen stable primary carries traffic; validated alternatives remain reserves. */
    fun connectedPool(servers: List<Server>, state: (Server) -> ServerState, net: String, now: Long, gaming: Boolean): List<Server> =
        servers.distinctBy { it.id }.filter { vlessServer(it) && state(it).works && !RouteStabilityMemory.session.cooling(net,it.id,state(it).maskFor(net),now) }.let { candidates ->
            val routes = servicePool(candidates, state, net, now)
            val usable = routes
            if (gaming) usable.sortedBy { ServerChoice.latencyScore(state(it).evidenceFor(net),state(it).realMs) }
            else usable.sortedWith(ServerChoice.downloadOrder(state,net,now))
        }.let(::independent).let { AutoPrimaryPolicy.choose(it,state,net,now,gaming) }.take(ACTIVE)
    /** WireGuard endpoints of one account are alternatives, not independent channels.
     *  Same for the rest of the catalog: one /24 subnet or one SNI is a single blocking
     *  blast radius (community RKN scanners harvest by ASN and by TLS fingerprint), so
     *  Auto keeps at most one representative of each. */
    fun independent(servers: List<Server>): List<Server> {
        val secrets = HashSet<String>(); val subnets = HashSet<String>(); val snis = HashSet<String>()
        return servers.filter { s ->
            val freshSecret = s.protocol != "wireguard" || secrets.add(s.secret)
            val freshSubnet = if (s.address.isIpv4()) subnets.add(s.address.substringBeforeLast('.')) else true
            freshSecret && freshSubnet && (s.sni.isBlank() || snis.add(s.sni))
        }
    }
    /** Manual selections are explicit; diversity pruning belongs to Auto, not manual masks. */
    fun launchPool(servers: List<Server>, automatic: Boolean): List<Server> {
        val remote = servers.distinctBy { it.id }.filter(ServerOnlyPolicy::userRoute)
        return if (automatic) independent(remote.filter(::vlessServer)) else remote
    }
    private fun String.isIpv4(): Boolean {
        val parts = split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull() in 0..255 }
    }
    fun clean(st: AppState): AppState {
        val keep = st.servers.filter(::allowed)
        if (keep.size == st.servers.size) return st
        val ids = keep.mapTo(HashSet()) { it.id }
        return st.copy(servers=keep, states=st.states.filterKeys { it in ids },
            settings=st.settings.copy(netServers=st.settings.netServers.mapValues { (_,v) -> v.filter { it in ids } }))
    }
    fun serviceRank(s: ServerState, net: String, now: Long, services: Set<String> = setOf("youtube", "telegram")): Int {
        val relevant = services.intersect(setOf("youtube", "telegram"))
        if (relevant.isEmpty()) return 1
        if (s.serviceNetwork != net || s.serviceMask != s.maskFor(net) || s.serviceCheckedAt <= 0 ||
            now < s.serviceCheckedAt || now - s.serviceCheckedAt > 5 * 60000L) return 1
        val results = relevant.map { if (it == "youtube") s.ytOk else s.tgOk }
        if (results.any { it == false }) return 2
        return if (results.all { it == true }) 0 else 1
    }
    fun telegramRank(s: ServerState, net: String, now: Long): Int = serviceRank(s, net, now, setOf("telegram"))
    fun <T> servicePool(list: List<T>, state: (T) -> ServerState, net: String, now: Long): List<T> {
        // HTTPS alone is not proof that a native Telegram connection works.
        // Unknown, expired and different-mask samples must be revalidated before Auto selects them.
        val profile=AutoChecks.current
        return list.filter { val s=state(it)
            s.works && s.serviceNetwork==net && s.serviceMask==s.maskFor(net) &&
                AutoChecks.fresh(s.autoChecksKey,profile,AutoLatencyPolicy.MAX_MS,s.autoChecksAt,now) &&
                s.realMs in 1..AutoLatencyPolicy.MAX_MS && (!profile.telegram || s.tgOk==true && s.tgMs in 1..AutoLatencyPolicy.MAX_MS) &&
                (!profile.youtube || s.ytOk==true)
        }
    }
}
