package com.vlesscardvpn.core

/** One bounded emergency core: interleave a host's current route and remembered compatible masks.
 * Scheduling only: every variant must still pass the complete selected profile. */
object RecoveryWavePolicy {
    fun <T> arrange(current:List<T>, alternatives:List<List<T>>, selected:Int):List<T> {
        val out=mutableListOf<T>()
        for(i in current.indices) {
            out+=current[i]
            alternatives.getOrNull(i)?.firstOrNull()?.let {out+=it}
        }
        for(i in current.indices) out+=alternatives.getOrNull(i).orEmpty().drop(1).take(1)
        return out.distinct().take(minOf(6,ProbeConcurrencyPolicy.normalize(selected)*2))
    }
    fun budgetMs(variants:Int,selected:Int):Long {
        val slots=ProbeConcurrencyPolicy.normalize(selected)
        val batches=(variants.coerceAtLeast(1)+slots-1)/slots
        return (7500L*batches).coerceAtMost(18000L)
    }
    fun confirmDelayMs(auto:Boolean)=if(auto) 1000L else 3000L
}
