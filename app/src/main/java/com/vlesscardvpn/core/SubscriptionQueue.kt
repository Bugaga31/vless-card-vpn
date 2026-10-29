package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/** A local source queue, not a proxy chain or evidence that an untested server works. */
object SubscriptionQueue {
    const val WINDOW=64
    const val PER_SOURCE=2
    const val FRESH_MS=6*3600_000L
    data class Quality(val source:String,val observed:Int,val score:Double)
    fun quality(configured:List<String>,catalog:List<Server>,state:(Server)->ServerState,net:String,now:Long):List<Quality> {
        val sources=(configured+catalog.map {it.source}).distinct()
        val observations=HashMap<String,MutableMap<String,Pair<Long,Double>>>()
        catalog.distinctBy {it.id}.forEach {s ->
            val ss=state(s);val e=ss.evidenceFor(net)
            if(e.checks<=0 || e.at<=0 || now<e.at || now-e.at>FRESH_MS) return@forEach
            val service=AutoEligibility.telegramRank(ss,net,now)
            val utility=when {e.streak>0 || service==2 -> 0.0; e.successes<=0 -> 0.0; else ->
                (.65*e.ema+.35*(1.0-e.latency.coerceIn(0.0,2000.0)/2000.0)).coerceIn(0.0,1.0)}
            // Do not count a hundred credentials/transports to one host as a hundred independent successes.
            val host=s.address.lowercase(java.util.Locale.ROOT).removeSuffix(".").removeSurrounding("[","]")
            val map=observations.getOrPut(s.source){HashMap()};val old=map[host]
            if(old==null || e.at>old.first) map[host]=e.at to utility
        }
        return sources.map {source ->
            val samples=observations[source].orEmpty().values.sortedByDescending {it.first}.take(64)
            Quality(source,samples.size,(2.0+samples.sumOf {it.second})/(4.0+samples.size))
        }.sortedByDescending {it.score}
    }
    /** Keep leaders while giving a rotating challenger a slot. Filter refresh cooldowns BEFORE this call. */
    fun fetchOrder(ranked:List<String>,round:Int,limit:Int=AutoDiscoveryPolicy.SOURCES):List<String> {
        if(limit<=0) return emptyList()
        val all=ranked.distinct();if(all.size<=limit) return all
        val head=all.take(limit-1);val tail=all.drop(limit-1)
        return head+tail[round.coerceAtLeast(0)%tail.size]
    }
    /** Bounded source round-robin; preserve a genuinely proven local leader, never fabricate routes. */
    fun window(candidates:List<Server>,ranked:List<String>,limit:Int=WINDOW,leader:String?=null):List<Server> {
        if(limit<=0) return emptyList()
        val unique=candidates.distinctBy {it.id}
        val groups=unique.groupBy {it.source}
        val order=(ranked+groups.keys).distinct().filter {it in groups}
        val queues=order.associateWith {groups.getValue(it).iterator()}
        val out=ArrayList<Server>();val seen=HashSet<String>()
        unique.firstOrNull {it.id==leader}?.let {out+=it;seen+=it.id}
        while(out.size<limit) {
            var added=false
            for(source in order) {
                val it=queues.getValue(source);var n=0
                while(it.hasNext() && n<PER_SOURCE && out.size<limit) {
                    val server=it.next();if(seen.add(server.id)){out+=server;n++;added=true}
                }
            }
            if(!added) break
        }
        return out
    }
}
