package com.vlesscardvpn.core

import java.net.InetAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Interleave resolver-provided families; preserve its first preference and order within a family.
 * No invented NAT64 address and no permanent IPv4-only fallback.
 */
object DualStackOrder {
    fun order(addresses:List<InetAddress>):List<InetAddress> {
        val unique=addresses.distinctBy {it.hostAddress}
        if(unique.isEmpty()) return emptyList()
        val (first,other)=unique.partition {(it.address.size==16)==(unique.first().address.size==16)}
        val result=mutableListOf<InetAddress>()
        for(i in 0 until maxOf(first.size,other.size)) {first.getOrNull(i)?.let(result::add);other.getOrNull(i)?.let(result::add)}
        return result.take(4)
    }
}

/** A small Happy-Eyeballs-inspired TCP HINT, not full RFC8305 or authenticated VPN proof.
 * <=4 addresses, <=2 simultaneous sockets, 250ms stagger and one shared deadline.
 * Return >0 elapsed ms, 0 only for definite failures of ALL addresses, -1 for unknown.
 */
object StaggeredConnect {
    suspend fun <T> hint(candidates:List<T>,budgetMs:Long,
        connect:suspend(T,Long)->Boolean?):Int = coroutineScope {
        val list=candidates.take(4)
        if(list.isEmpty() || budgetMs<=0) return@coroutineScope -1
        val started=System.nanoTime();val budget=budgetMs.coerceAtMost(60000L)
        val responses=Channel<Boolean?>(list.size);val sem=Semaphore(2)
        val jobs=list.mapIndexed {i,target->launch {
            delay(i*250L)
            val result=sem.withPermit {
                val left=budget-(System.nanoTime()-started)/1000000L
                if(left<=0) null else try {connect(target,left)}
                catch(e:CancellationException) {throw e} catch(_:Exception) {false}
            }
            responses.send(result)
        }}
        try {
            withTimeoutOrNull(budget) {
                var unknown=false
                repeat(list.size) {
                    when(responses.receive()) {
                        true->return@withTimeoutOrNull ((System.nanoTime()-started)/1000000L).toInt().coerceAtLeast(1)
                        null->unknown=true
                        false->Unit
                    }
                }
                if(unknown) -1 else 0
            } ?: -1
        } finally {jobs.forEach {it.cancel()};responses.close()}
    }
}
