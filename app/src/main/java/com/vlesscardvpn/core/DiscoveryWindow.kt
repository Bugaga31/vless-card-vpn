package com.vlesscardvpn.core

/** Order hints, never permanently hide a route from revalidation. Admission stays strict. */
object DiscoveryWindow {
    fun <T> pool(list:List<T>,cooling:(T)->Boolean,serviceRank:(T)->Int):List<T> =
        list.sortedWith(compareBy<T> {cooling(it)}.thenBy {serviceRank(it)})
}
