package com.vlesscardvpn.core

/** Relax timing only; UDP packet loss/spread and Telegram response failures remain failures. */
object LatencyOnlyProbe {
    fun proof(p:Probe,profile:AutoCheckProfile,stableIgnoringLatency:Boolean?,callsIgnoringLatency:Boolean?):Probe {
        val relaxed=p.copy(stableOk=if(profile.stability) stableIgnoringLatency else p.stableOk,
            callsOk=if(profile.voice) callsIgnoringLatency else p.callsOk)
        return p.copy(latencyOnlyOk=profile.accepts(relaxed,Int.MAX_VALUE))
    }
}
