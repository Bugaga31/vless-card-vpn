package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** Labels are hints, not IP geolocation. Never silently fall back to a known Russian exit in Auto. */
object AutoEligibility {
    const val SERVICE_TTL_MS = 5 * 60_000L
    fun allowed(server: Server): Boolean = CloudControl.usable(server) && !server.name.contains("🇷🇺") && Countries.of(server.name) != "RU"
    const val TOP = 8
    /** Every returned route is handed to Xray's multi-outbound pool, not held as a hidden spare. */
    fun connectedPool(servers: List<Server>, state: (Server) -> ServerState, net: String, now: Long, gaming: Boolean): List<Server> =
        connectionPool(servers.distinctBy { it.id }.filter { allowed(it) && state(it).works },state,net,now).let { routes ->
            if (gaming) routes.sortedBy { ServerChoice.latencyScore(state(it).evidenceFor(net),state(it).realMs) }
            else routes.sortedWith(ServerChoice.downloadOrder(state,net,now))
        }.let { ServerChoice.diverse(independent(it), TOP) }
    /** WireGuard endpoints of one account are alternatives, not independent channels. */
    fun independent(servers: List<Server>): List<Server> {
        val keys = HashSet<String>()
        return servers.filter { it.protocol != "wireguard" || keys.add(it.secret) }
    }
    /** Admission is not deletion: keep manual configurations and all accumulated learning. */
    fun clean(st: AppState): AppState {
        if (st.settings.mode != com.vlesscardvpn.model.Mode.AUTO) return st
        val rejected = st.servers.filterNot(::allowed).mapTo(HashSet()) { it.id }
        if (rejected.isEmpty()) return st
        return st.copy(states = st.states.mapValues { (id, state) ->
            if (id in rejected && state.selected) state.copy(selected = false) else state
        }, settings = st.settings.copy(netServers = st.settings.netServers.mapValues { (_, ids) -> ids.filterNot { it in rejected } }))
    }
    fun serviceRank(s: ServerState, net: String, now: Long): Int {
        if (s.serviceNetwork != net || s.serviceMask != s.maskFor(net) || s.serviceCheckedAt <= 0 ||
            now < s.serviceCheckedAt || now - s.serviceCheckedAt > SERVICE_TTL_MS) return 1
        if (s.serviceYtOk == false || s.serviceTgOk == false) return 2
        return if (s.serviceYtOk == true && s.serviceTgOk == true) 0 else 1
    }
    /** Connectivity alone is not success in Auto: require real video and MTProto proofs. */
    fun servicesWork(probe: Probe): Boolean = probe.works && probe.ytOk == true && probe.tgOk == true

    /** Generic HTTPS admission is explicitly NOT a service PASS. Confirmed service FAIL is excluded. */
    fun connectionWorks(probe: Probe): Boolean = probe.works && probe.httpsHosts >= HttpsTransferProbe.REQUIRED_HOSTS &&
        probe.ytOk != false && probe.tgOk != false
    fun httpsFresh(s: ServerState, net: String, now: Long): Boolean =
        s.httpsHosts >= HttpsTransferProbe.REQUIRED_HOSTS && s.httpsNetwork == net && s.httpsMask == s.maskFor(net) &&
            s.httpsCheckedAt > 0 && now - s.httpsCheckedAt in 0..SERVICE_TTL_MS
    fun recordConnection(s: ServerState, net: String, mask: String, now: Long, p: Probe): ServerState =
        if (!p.httpsChecked) s else s.copy(httpsNetwork=net,httpsMask=mask,httpsCheckedAt=now,httpsHosts=p.httpsHosts)
    /** Comparison may examine several B routes, but never mix A and B. */
    fun <T> comparisonPool(list: List<T>, state: (T) -> ServerState, net: String, now: Long): List<T> {
        val proven = servicePool(list,state,net,now)
        return proven.ifEmpty { list.filter { state(it).works && httpsFresh(state(it),net,now) && serviceRank(state(it),net,now) != 2 } }
    }
    /** A fallback is one measured leader, not a balancer of unconfirmed configurations. */
    fun <T> connectionPool(list: List<T>, state: (T) -> ServerState, net: String, now: Long): List<T> {
        val candidates = comparisonPool(list,state,net,now)
        return if (candidates.any { serviceRank(state(it),net,now)==0 }) candidates
            else candidates.sortedWith(Comparator { a,b -> ServerChoice.compareDownload(state(a),state(b),net,now) }).take(1)
    }
    fun connectionVerdict(probe: Probe): Boolean? = when {
        Thread.currentThread().isInterrupted -> null
        !probe.httpsChecked -> null
        probe.httpsHosts < HttpsTransferProbe.REQUIRED_HOSTS -> null // control resources may be unavailable
        probe.ytOk == false || probe.tgOk == false -> false
        else -> true // connectivity only, never write invented service flags
    }

    fun learningVerdict(probe: Probe, requireServices: Boolean): Boolean? = when {
        Thread.currentThread().isInterrupted -> null
        !probe.works -> false
        !requireServices -> true
        probe.ytOk == false || probe.tgOk == false -> false
        probe.ytOk == true && probe.tgOk == true -> true
        else -> null
    }

    /** A new attempted check replaces old proof even when both service results are inconclusive. */
    fun recordServices(state: ServerState, net: String, mask: String, now: Long, probe: Probe): ServerState =
        state.copy(serviceNetwork = net, serviceMask = mask, serviceCheckedAt = now,
            serviceYtOk = probe.ytOk, serviceTgOk = probe.tgOk)

    /** A failed actual chain cannot keep cached positive evidence for the next button press.
     * This revokes proof, NOT a per-server failure verdict: a balancer/resource failure isn't attributable
     * to every member. Preserve history and newer/concurrent observations or a changed mask/network.
     */
    fun invalidateChainProof(s: ServerState, net: String, mask: String, startedAt: Long): ServerState {
        if (s.serviceNetwork != net || s.serviceMask != mask || s.maskFor(net) != mask ||
            s.serviceCheckedAt > startedAt) return s
        val invalidateSpeed = s.speedNetwork == net && s.speedMask == mask && s.speedCheckedAt <= startedAt
        return s.copy(selected=false, serviceCheckedAt=0, httpsCheckedAt=0,
            speedCheckedAt=if (invalidateSpeed) 0 else s.speedCheckedAt)
    }

    fun <T> servicePool(list: List<T>, state: (T) -> ServerState, net: String, now: Long): List<T> {
        // A shared balancer sends real Telegram/video connections to EVERY member.
        // Never dilute a proven pool with generic-HTTPS-only or failed routes just to reach eight.
        // Unknown, stale and failed routes remain search candidates, never connected outbounds.
        return list.filter { state(it).works && serviceRank(state(it),net,now) == 0 }
    }
}
