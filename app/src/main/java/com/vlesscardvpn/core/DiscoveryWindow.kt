package com.vlesscardvpn.core

/** Cheap eligibility pass BEFORE a bounded expensive ranking window, not after it.
 * Deferred recovery is retained only when the whole catalog has no ready route.
 */
object DiscoveryWindow {
    fun <T> pool(list:List<T>,cooling:(T)->Boolean,serviceRank:(T)->Int):List<T> {
        val possible=list.filter {serviceRank(it)<2}.ifEmpty {list}
        return possible.filterNot(cooling).ifEmpty {possible}
    }
}
