package com.vlesscardvpn.core

/** Reserve bounded discovery slots for locally answering routes, even if old service proof expired/failed. */
object KnownRouteQueue {
    fun <T> merge(primary:List<T>,known:List<T>,limit:Int,key:(T)->String):List<T> {
        if(limit<=0) return emptyList()
        val local=known.distinctBy(key).take(minOf(5,maxOf(1,limit/3)))
        return (local+primary+known).distinctBy(key).take(limit)
    }
}
