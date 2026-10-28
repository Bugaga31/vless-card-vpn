package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** One stable primary; other measured routes stay as reserves, not random connection destinations. */
object AutoPrimaryPolicy {
    fun choose(ranked: List<Server>, state: (Server) -> ServerState, net: String, now: Long, gaming: Boolean): List<Server> {
        val leader = ranked.firstOrNull() ?: return emptyList()
        val current = ranked.firstOrNull { state(it).selected } ?: return listOf(leader)
        if (current.id == leader.id) return listOf(current)
        if (gaming) {
            val old = state(current).realMs
            val next = state(leader).realMs
            return listOf(if (old > 0 && next > 0 && next <= old * 0.8 && old - next >= 15) leader else current)
        }
        val old = ServerChoice.measuredSpeed(state(current),net,now).toLong()
        val next = ServerChoice.measuredSpeed(state(leader),net,now).toLong()
        // No proof of an improvement means no speed-only reconnect. Failed/expired service
        // routes are removed before this policy, so stability never preserves a known failure.
        val worthwhile = next > 0 && (old <= 0 || next >= old * 125 / 100 && next - old >= 256)
        return listOf(if (worthwhile) leader else current)
    }
}
