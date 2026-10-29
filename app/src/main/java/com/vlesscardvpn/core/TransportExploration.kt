package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import java.util.Locale

/** Reserve at most two fast-probe slots for distinct existing transport/security families.
 * Never creates endpoints, changes server contracts, bypasses eligibility/cooldown or displaces the leader.
 */
object TransportExploration {
    private fun family(s: Server) = listOf(s.protocol, s.network, s.security)
        .joinToString("|") { it.lowercase(Locale.ROOT) }
    fun representatives(catalog: List<Server>, limit: Int = 3): List<Server> =
        catalog.distinctBy(::family).take(limit.coerceIn(0, 8))
    fun merge(ranked: List<Server>, eligible: List<Server>, limit: Int): List<Server> {
        if (limit <= 0) return emptyList()
        val byId = eligible.associateBy { it.id }
        val known = ranked.mapNotNull { byId[it.id] }.distinctBy { it.id }
        val leader = known.firstOrNull() ?: return emptyList()
        val seen = hashSetOf(family(leader))
        val challengers = eligible.filter { seen.add(family(it)) }.take(minOf(2, limit - 1))
        return (listOf(leader) + challengers + known).distinctBy { it.id }.take(limit)
    }
}
