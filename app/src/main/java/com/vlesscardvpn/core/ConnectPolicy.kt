package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Bounded foreground discovery. Cloud is a hint; only a phone-side HTTPS success can start the tunnel. */
object ConnectPolicy {
    const val QUICK_BUDGET_MS = 3500L
    class StartGate {
        private val taken = java.util.concurrent.atomic.AtomicBoolean()
        val started get() = taken.get()
        fun claim() = taken.compareAndSet(false, true)
    }
    const val FIRST = 8
    const val NEXT = 16
    const val BACKGROUND = 24
    fun candidates(list: List<Server>, cloud: List<Server>, remembered: Set<String>,
                   cooling: (Server) -> Boolean = { false }, score: (Server) -> Int): List<Server> {
        val byId = list.associateBy { it.id }
        val ranked = list.filter { it.id in remembered }.sortedBy(score) + cloud.mapNotNull { byId[it.id] } + list.sortedBy(score)
        val unique = ranked.distinctBy { it.id }
        val (deferred, ready) = unique.partition(cooling)
        return ServerChoice.diverse(ready + deferred, FIRST)
    }
}
