package com.vlesscardvpn.core

/** Count only distinct current proof-bearing routes, never raw TCP answers. */
object FoundRoutePolicy {
    const val MAX_VISIBLE=64
    fun <T> rank(values:List<T>,valid:(T)->Boolean,latency:(T)->Int,key:(T)->String):List<T> =
        values.filter(valid).sortedBy(latency).distinctBy(key).take(MAX_VISIBLE)
}
