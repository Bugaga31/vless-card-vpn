package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Candidate-probe priority, never permission to launch a live route. Not a calibrated success probability. */
object AdaptiveRouteRank {
    private const val HALF_LIFE_MS=30*60_000.0
    fun priority(state:ServerState,net:String,now:Long,cloud:Double?=null):Double {
        val e=state.evidenceFor(net)
        val age=if(e.at>0 && now>=e.at) (now-e.at).toDouble() else Double.POSITIVE_INFINITY
        val decay=exp(-ln(2.0)*age/HALF_LIFE_MS)
        // Beta(2,2) shrinks tiny/stale samples toward neutral; old failures are not permanent bans.
        val n=e.checks.coerceIn(0,1000)*decay
        val wins=if(e.checks>0) n*e.successes.coerceIn(0,e.checks).toDouble()/e.checks else 0.0
        val mean=(wins+2)/(n+4)
        val confidence=(mean-sqrt((mean*(1-mean)/(n+5)).coerceAtLeast(0.0))).coerceIn(0.0,1.0)
        val recent=e.ema.coerceIn(0.0,1.0)*decay + .5*(1-decay)
        val speed=ServerChoice.measuredSpeed(state,net,now)
        val throughput=if(speed>0) (ln(1+speed/1000.0)/ln(51.0)).coerceIn(0.0,1.0) else 0.0
        val tail=if(e.latency>0 && decay>.1) ((e.latency+2*e.jitter.coerceAtLeast(0.0))/10_000).coerceIn(0.0,.25) else .05
        val hint=cloud?.takeIf {it.isFinite() && it in 0.0..1.0} ?: .5
        return (.45*confidence+.30*recent+.20*throughput+.05*hint-tail).coerceIn(-.25,1.0)
    }
    fun order(list:List<Server>,state:(Server)->ServerState,net:String,now:Long,cloud:(Server)->Double?):List<Server> =
        list.distinctBy{it.id}.map {it to priority(state(it),net,now,cloud(it))}
            .sortedWith(compareByDescending<Pair<Server,Double>>{it.second}.thenBy{it.first.id}).map{it.first}
}
