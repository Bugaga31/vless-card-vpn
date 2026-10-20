package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Cheap reachability breadth from the old search, without a full unbounded catalog scan.
 * RAM-only cursors partitioned by network; retries don't always stop at the same first 24 links.
 */
class ForegroundDiscovery {
    companion object {
        const val WINDOW = 128
        const val HTTPS_FINALISTS = 24
        fun serviceWave(ordered: List<Server>, attempted: Set<String>): List<Server> =
            ServerChoice.diverse(ordered.filter { it.id !in attempted }, ServiceProbePolicy.FINALISTS)
    }
    private val offsets = linkedMapOf<String, Int>()
    @Synchronized fun window(catalog: List<Server>, network: String): List<Server> {
        val routes = ServerChoice.diverse(catalog, catalog.size)
        if (routes.isEmpty()) return emptyList()
        val start = (offsets.remove(network) ?: 0).mod(routes.size)
        val count = minOf(WINDOW,routes.size)
        offsets[network] = (start + count).mod(routes.size)
        while (offsets.size > 16) offsets.remove(offsets.keys.first())
        return (0 until count).map { routes[(start + it).mod(routes.size)] }
    }
}
