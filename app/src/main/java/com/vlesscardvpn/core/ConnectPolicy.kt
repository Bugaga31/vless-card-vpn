package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Bounded foreground discovery. Cloud is a hint; only a phone-side HTTPS success can start the tunnel. */
object ConnectPolicy {
    const val QUICK_BUDGET_MS = 3500L
    data class FastStart(val candidates: Int, val parallel: Int, val stopAfter: Int, val budgetMs: Long)
    fun fastStart(background: Boolean = false) = FastStart(
        candidates = if (background) 3 else FIRST,
        parallel = if (background) 2 else 4,
        stopAfter = 1,
        budgetMs = if (background) 1800L else QUICK_BUDGET_MS,
    )
    class StartGate {
        private val taken = java.util.concurrent.atomic.AtomicBoolean()
        val started get() = taken.get()
        fun claim() = taken.compareAndSet(false, true)
        /** Do not consume the first-start slot until a working route is committed. */
        @Synchronized fun claimSelected(select: () -> Boolean): Boolean {
            if (taken.get() || !select()) return false
            return taken.compareAndSet(false, true)
        }
    }
    const val FIRST = 8
    const val NEXT = 16
    const val BACKGROUND = 24
    fun candidates(list: List<Server>, cloud: List<Server>, remembered: Set<String>,
                   cooling: (Server) -> Boolean = { false }, serviceRank: (Server) -> Int = { 1 },
                   score: (Server) -> Int): List<Server> {
        val byId = list.associateBy { it.id }
        val uniqueList = list.distinctBy { it.id }
        val hasPossibleServiceRoute = uniqueList.any { serviceRank(it) < 2 }
        val eligible = if (hasPossibleServiceRoute) uniqueList.filter { serviceRank(it) < 2 } else uniqueList
        val cloudIds = cloud.mapNotNull { byId[it.id] }.map { it.id }.toSet()
        val unique = eligible.sortedWith(compareBy<Server> { serviceRank(it) }.thenBy { score(it) }
            .thenBy { if (it.id in remembered) 0 else 1 }.thenBy { if (it.id in cloudIds) 0 else 1 })
        val (deferred, ready) = unique.partition(cooling)
        val eligibleCloud = cloud.filter { hint -> ready.any { it.id == hint.id } }
        return PublicCloudQueue.merge(ready + deferred, eligibleCloud, list, FIRST, slots = FIRST / 2)
    }
}
