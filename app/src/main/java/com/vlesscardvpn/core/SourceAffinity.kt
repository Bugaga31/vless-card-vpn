package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** Local, network-specific source learning. Source quality proposes trials, never proves a new server works.
 * Private subscription URLs stay on the phone; public provenance is supplied only by the public collector.
 */
object SourceAffinity {
    fun order(candidates: List<Server>, catalog: List<Server>, state: (Server) -> ServerState,
              net: String, now: Long, publicSources: (Server) -> List<String> = { emptyList() }): List<Server> {
        fun sources(s: Server) = (publicSources(s) + s.provenance.filter { it.startsWith("https://") }).distinct().take(16)
        val evidence = HashMap<String,MutableList<Double>>()
        catalog.distinctBy { it.id }.forEach { s ->
            val ss=state(s); val local=ss.evidenceFor(net)
            val speed=ServerChoice.measuredSpeed(ss,net,now)
            if (speed<=0 && (local.checks<=0 || local.at<=0 || now<local.at || now-local.at>6*3600000L)) return@forEach
            val reliability=if(local.checks>0) (local.successes+2.0)/(local.checks+4.0) else .5
            val serviceConfidence = when (AutoEligibility.serviceRank(ss,net,now)) { 0 -> 1.0; 2 -> 0.0; else -> .5 }
            val utility=serviceConfidence * reliability * (.25 + .75*(speed.coerceAtMost(50000)/50000.0))
            sources(s).forEach { evidence.getOrPut(it){mutableListOf()}.add(utility) }
        }
        val scores=evidence.mapValues { (_,v) -> v.average() * v.size/(v.size+3.0) }
        return candidates.distinctBy { it.id }.mapNotNull { s ->
            sources(s).mapNotNull { scores[it] }.maxOrNull()?.let { s to it }
        }.sortedByDescending { it.second }.map { it.first }
    }
}
