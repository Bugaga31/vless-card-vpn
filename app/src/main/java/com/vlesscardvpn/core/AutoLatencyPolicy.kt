package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState

/** End-to-end cold HTTPS and MTProto response times, not ICMP ping or promised app latency.
 * Faster than 50ms is welcome. Unknown historical timing cannot satisfy the 500ms gate.
 */
object AutoLatencyPolicy {
    const val MAX_MS=500
    fun accepts(p:Probe):Boolean = p.works && p.tgOk==true && p.realMs in 1..MAX_MS && p.tgMs in 1..MAX_MS
    fun accepts(s:ServerState):Boolean = s.works && s.tgOk==true && s.realMs in 1..MAX_MS && s.tgMs in 1..MAX_MS
    /** A single load spike is not a reason to tear down a working tunnel. */
    fun slowStreak(previous:Int,p:Probe):Int = when {
        !p.works || p.tgOk!=true || p.tgMs<=0 -> 0
        accepts(p) -> 0
        else -> (previous+1).coerceAtMost(3)
    }
}
