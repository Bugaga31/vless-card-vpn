package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState

/** End-to-end cold HTTPS and MTProto response times, not ICMP ping or promised app latency.
 * Faster than 50ms is welcome. Unknown historical timing cannot satisfy the 500ms gate.
 */
object AutoLatencyPolicy {
    const val MAX_MS=com.vlesscardvpn.health.ModuleHealthPolicy.MAX_LATENCY_MS
    fun accepts(p:Probe):Boolean = com.vlesscardvpn.health.ModuleHealthPolicy.fastLink(p.works,p.realMs,p.tgOk,p.tgMs)
    fun accepts(s:ServerState):Boolean = com.vlesscardvpn.health.ModuleHealthPolicy.fastLink(s.works,s.realMs,s.tgOk,s.tgMs)
    /** A single load spike is not a reason to tear down a working tunnel. */
    fun slowStreak(previous:Int,p:Probe):Int = when {
        !p.works || p.tgOk!=true || p.tgMs<=0 -> 0
        accepts(p) -> 0
        else -> (previous+1).coerceAtMost(3)
    }
}
