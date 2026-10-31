package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import kotlin.math.exp

/** Session-local readout trained over a restricted real FlyWire-derived reservoir. No cloud, app inventory or
 * admission authority. No training on unknown/cancelled results. Cold start preserves order. */
class RokiRanker {
    private data class Model(val weights:DoubleArray=FlyReservoir.initialWeights,var samples:Int=0)
    private val models=linkedMapOf<String,Model>()
    private val featureCache=linkedMapOf<List<Double>,DoubleArray>()
    private fun features(s:ServerState):DoubleArray {
        val raw=doubleArrayOf(1.0,
        s.tcpMs.coerceIn(0,2000)/2000.0,s.realMs.coerceIn(0,2000)/2000.0,
        if(s.bigOk==true) 1.0 else 0.0,
        s.okCount.toDouble().coerceAtLeast(0.0)/(s.okCount.toDouble().coerceAtLeast(0.0)+s.failCount.toDouble().coerceAtLeast(0.0)+1.0))
        val key=raw.toList()
        return featureCache.getOrPut(key) {FlyReservoir.encode(raw)}.also {
            while(featureCache.size>128) featureCache.remove(featureCache.keys.first())
        }
    }
    private fun predict(m:Model,x:DoubleArray):Double {
        val z=m.weights.indices.sumOf {m.weights[it]*x[it]}.coerceIn(-20.0,20.0)
        return 1.0/(1.0+exp(-z))
    }
    @Synchronized fun observe(context:String,before:ServerState,verdict:Boolean?,cancelled:Boolean=false) {
        if(verdict==null || cancelled) return
        val m=models.getOrPut(context) {Model()}
        while(models.size>24) models.remove(models.keys.first())
        val x=features(before);val error=(if(verdict) 1.0 else 0.0)-predict(m,x)
        for(i in m.weights.indices) m.weights[i]=(m.weights[i]+0.1*error*x[i]).coerceIn(-8.0,8.0)
        m.samples=(m.samples+1).coerceAtMost(1000000)
    }
    @Synchronized fun samples(context:String)=models[context]?.samples ?: 0
    @Synchronized fun order(context:String,servers:List<Server>,state:(Server)->ServerState):List<Server> {
        val m=models[context] ?: return servers
        if(m.samples<8) return servers
        // Bound inference work; never discard the remaining candidates.
        return servers.take(64).sortedByDescending {predict(m,features(state(it)))}+servers.drop(64)
    }
}
object Roki {
    val session=RokiRanker()
    fun context(network:String,profile:AutoCheckProfile,limit:Int,testUrl:String)=
        network+"|"+AutoChecks.key(profile,limit)+"|"+limit+"|"+testUrl
}
