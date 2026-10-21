package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Only the wave size is bounded, never the number of configurations to check. */
object FullCatalogChecks {
    const val WAVE_SIZE = 12

    fun ordered(catalog: List<Server>, tcp: Map<String, Int>, learned: Map<String, Int> = emptyMap()): List<Server> =
        catalog.distinctBy { it.id }.sortedWith(compareBy<Server> {
            tcp[it.id]?.takeIf { ms -> ms > 0 } ?: Int.MAX_VALUE
        }.thenBy { learned[it.id] ?: Int.MAX_VALUE })

    fun waves(ordered: List<Server>): List<List<Server>> = ordered.chunked(WAVE_SIZE)
}
