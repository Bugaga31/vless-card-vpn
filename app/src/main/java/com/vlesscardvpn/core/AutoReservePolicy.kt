package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** Fill missing, already HTTPS-working reserves before optional catalog/mask/download maintenance.
 * Never re-test a fresh service PASS just to fill another slot; UNKNOWN retries are paced, not penalized.
 */
object AutoReservePolicy {
    /** Periodic mask evolution is not recovery: do not invalidate a healthy Auto chain/proof.
     * Explicit manual tests and measured Auto mask comparison remain available. */
    fun periodicMaskChangeAllowed(automatic: Boolean, healthyRoute: Boolean): Boolean = !automatic || !healthyRoute
    const val BATCH = 4
    const val RETRY_MS = 30_000L
    const val ROUND_MS = 18_000L
    fun pending(servers: List<Server>, state: (Server) -> ServerState, net: String, now: Long): List<Server> =
        ServerChoice.diverse(servers.filter { server ->
            val s=state(server)
            val same=s.serviceNetwork==net && s.serviceMask==s.maskFor(net)
            AutoEligibility.allowed(server) && s.works && AutoEligibility.serviceRank(s,net,now)!=0 &&
                (!same || s.serviceCheckedAt<=0 || now-s.serviceCheckedAt>=RETRY_MS)
        }.sortedBy { state(it).serviceCheckedAt },BATCH)
}
