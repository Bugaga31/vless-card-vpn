package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** Prior endpoints and per-network history are hints. Handoff requires new body probes. */
object AutoHandoffPolicy {
    const val TOTAL_MS = 15_000L
    const val MAX_CANDIDATES = 8
    fun resetRuntime(state: ServerState, network: String): ServerState = state.forAutomaticNetwork(network).copy(
        tcpMs = -1, realMs = -1, checkedAt = 0, bigOk = null, ytOk = null, tgOk = null, kbps = 0,
        serviceNetwork = "", serviceMask = "", serviceCheckedAt = 0, speedCheckedAt = 0)
    fun candidates(previous: List<Server>, catalog: List<Server>, others: List<Server>): List<Server> {
        val current = catalog.associateBy { it.id }
        val hints = previous.take(MAX_CANDIDATES).mapNotNull { old ->
            current[old.id]?.takeIf { it.toJson().toString() == old.toJson().toString() }
        }
        val priorIds=previous.take(MAX_CANDIDATES).mapTo(HashSet()) { it.id }
        // A large old pool must not consume the whole wave before new-network challengers.
        val challengers=others.filter { it.id !in priorIds }
        return ServerChoice.diverse((hints.take(2) + challengers + others).filter(AutoEligibility::vlessServer).distinctBy { it.id }, MAX_CANDIDATES)
    }
}
