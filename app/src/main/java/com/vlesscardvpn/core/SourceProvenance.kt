package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Phone-local provenance. Never put these potentially private URLs in diagnostics or public reports. */
object SourceProvenance {
    fun merge(existing: List<Server>, incoming: List<Server>): List<Server> {
        val byId = existing.associateByTo(linkedMapOf()) { it.id }
        incoming.forEach { server ->
            val old = byId[server.id]
            byId[server.id] = if (old == null) server else old.copy(sources = (old.provenance + server.provenance).distinct().take(16))
        }
        return byId.values.toList()
    }
    fun replace(existing: List<Server>, source: String, incoming: List<Server>, selected: Set<String>): List<Server> {
        val incomingIds = incoming.mapTo(HashSet()) { it.id }
        val retained = existing.mapNotNull { server ->
            if (source !in server.provenance || server.id in incomingIds || server.id in selected) server
            else (server.provenance - source).takeIf { it.isNotEmpty() }?.let { origins ->
                server.copy(source = origins.first(), sources = origins)
            }
        }
        return merge(retained, incoming)
    }
}
