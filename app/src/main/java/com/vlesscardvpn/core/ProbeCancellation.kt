package com.vlesscardvpn.core

/** A probe may own HTTP calls and native sockets at the same time. Close every hook exactly once.
 * Registration after cancellation closes immediately; callbacks run outside the registry lock.
 */
class ProbeCancellation:AutoCloseable {
    private var closed=false
    private val hooks=mutableListOf<()->Unit>()
    @Synchronized fun isClosed()=closed
    fun add(hook:()->Unit) {
        val closeNow=synchronized(this) {if(closed) true else {hooks.add(hook);false}}
        if(closeNow) runCatching(hook)
    }
    override fun close() {
        val pending=synchronized(this) {
            if(closed) return
            closed=true;hooks.toList().also {hooks.clear()}
        }
        pending.forEach {runCatching(it)}
    }
}
