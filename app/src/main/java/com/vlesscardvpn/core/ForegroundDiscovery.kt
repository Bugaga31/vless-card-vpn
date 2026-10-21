package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Network-specific catalogue ordering and optional lightweight windows.
 * A window is not a limit on real checks: full discovery uses catalog().
 */
class ForegroundDiscovery {
    companion object {
        const val WINDOW = 128
        fun serviceWave(ordered: List<Server>, attempted: Set<String>): List<Server> =
            ServerChoice.diverse(ordered.filter { it.id !in attempted }, ServiceProbePolicy.FINALISTS)
    }
    private val offsets = linkedMapOf<String, Int>()
    /** The initial candidate count is not the catalog limit. Resume after the previous budget boundary. */
    @Synchronized fun catalog(catalog: List<Server>, network: String): List<Server> {
        val routes=ServerChoice.diverse(catalog,catalog.size)
        if(routes.isEmpty()) return emptyList()
        val start=(offsets["catalog|$network"] ?: 0).mod(routes.size)
        return routes.drop(start)+routes.take(start)
    }
    @Synchronized fun advanceCatalog(network: String, visited: Int, total: Int) {
        if(total<=0) return
        val key="catalog|$network";val old=offsets.remove(key) ?: 0
        offsets[key]=(old+visited.coerceAtLeast(1)).mod(total)
        while(offsets.size>16) offsets.remove(offsets.keys.first())
    }
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
