package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Reserve bounded challenger slots without displacing the best local leader or injecting endpoints. */
object PublicCloudQueue {
    fun merge(local: List<Server>, cloud: List<Server>, catalog: List<Server>, limit: Int, slots: Int = 2): List<Server> {
        if (limit <= 0) return emptyList()
        val allowed = catalog.associateBy { it.id }
        val ranked = local.mapNotNull { allowed[it.id] }.distinctBy { it.id }
        val challengers = cloud.mapNotNull { allowed[it.id] }.distinctBy { it.id }
        val head = ranked.take(1)
        val reserved = ServerChoice.diverse(challengers.filter { s -> head.none { it.id == s.id } }, minOf(slots.coerceAtLeast(0),limit-head.size))
        // Diversity keeps bounded work useful even with many clones of one endpoint.
        return ServerChoice.diverse((head + reserved + ranked + catalog.mapNotNull { allowed[it.id] }).distinctBy { it.id }, limit)
    }
}
