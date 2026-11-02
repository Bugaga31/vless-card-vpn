package com.vlesscardvpn.core

/** UDP reachability, not a voice-call, codec, TURN or quality guarantee. No direct fallback. */
object VoiceAvailabilityProbe {
    data class Target(val host:String,val port:Int)
    val targets=listOf(Target(UdpCallProbe.HOST,UdpCallProbe.PORT),Target("stun.cloudflare.com",3478))
    const val PACKETS=4
    data class Result(val ok:Boolean?,val ignoringLatency:Boolean?,val reason:String,val received:Int=0)
    fun assess(t:UdpCallProbe.Train,maxMs:Int):Result {
        if(!t.complete || t.sent!=PACKETS) return Result(null,null,"VOICE_INCOMPLETE",t.rttMs.size)
        val valid=t.rttMs.filter {it>0}
        if(valid.size<2) return Result(null,null,"VOICE_NO_RESPONSE",valid.size)
        val within=valid.maxOrNull()!!<=maxMs
        return Result(within,true,if(!within) "VOICE_LATENCY" else if(valid.size<PACKETS) "VOICE_AVAILABLE_LOSS" else "VOICE_AVAILABLE",valid.size)
    }
    fun check(maxMs:Int,remaining:()->Long,measure:(Target,Int)->UdpCallProbe.Train):Result {
        var last=Result(null,null,"VOICE_NO_RESPONSE")
        for(target in targets) {
            if(Thread.currentThread().isInterrupted) return Result(null,null,"CANCELLED")
            val left=remaining()
            if(left<250) return Result(null,null,"TIMEOUT")
            last=assess(measure(target,minOf(left,2000).toInt()),maxMs)
            // Confirmed UDP is not retried merely because RTT is high; consent remains separate.
            if(last.ignoringLatency==true) return last
        }
        return last
    }
}
