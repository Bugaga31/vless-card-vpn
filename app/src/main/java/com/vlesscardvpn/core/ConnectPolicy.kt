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
        val cloudIds = cloud.mapNotNull { byId[it.id] }.map { it.id }.toSet()
        val unique = list.distinctBy { it.id }.sortedWith(compareBy<Server> { score(it) }
            .thenBy { if (it.id in remembered) 0 else 1 }.thenBy { if (it.id in cloudIds) 0 else 1 })
        val (deferred, ready) = unique.partition(cooling)
        val eligibleCloud = cloud.filter { hint -> ready.any { it.id == hint.id } }
        return PublicCloudQueue.merge(ready + deferred, eligibleCloud, list, FIRST, slots = FIRST / 2)
    }
}
