package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState

/** End-to-end cold HTTPS and MTProto response times, not ICMP ping or promised app latency.
 * Faster than 50ms is welcome. Unknown historical timing cannot satisfy the configured gate.
 */
object AutoLatencyPolicy {
    val range: AutoLatencyRange.Range get() = Store.state.value.settings.let { AutoLatencyRange.safe(it.autoLatencyMinMs,it.autoLatencyMaxMs) }
    val MAX_MS: Int get() = range.max
    fun accepts(p:Probe):Boolean = range.accepts(p.works,p.realMs,p.tgOk,p.tgMs)
    fun accepts(s:ServerState):Boolean = range.accepts(s.works,s.realMs,s.tgOk,s.tgMs)
    /** A single load spike is not a reason to tear down a working tunnel. */
    fun slowStreak(previous:Int,p:Probe,maxMs:Int=MAX_MS):Int = when {
        !p.works || p.tgOk!=true || p.tgMs<=0 -> 0
        p.works && p.tgOk==true && p.realMs in 1..maxMs && p.tgMs in 1..maxMs -> 0
        else -> (previous+1).coerceAtMost(3)
    }
}
