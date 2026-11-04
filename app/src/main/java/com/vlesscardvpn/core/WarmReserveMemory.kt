package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Session-only proofs, never persisted or transferred across physical paths. Not a stability certificate. */
class WarmReserveMemory {
    private data class Entry(val path: NetworkHandoff.Snapshot, val server: Server, val mask: String,
        val testUrl: String, val at: Long)
    private val entries = linkedMapOf<String, Entry>()
    @Synchronized fun record(path: NetworkHandoff.Snapshot, server: Server, mask: String, testUrl: String, now: Long) {
        prune(now); entries.remove(server.id)
        entries[server.id] = Entry(path, server, mask, testUrl, now)
        while (entries.size > CAPACITY) entries.remove(entries.keys.first())
    }
    @Synchronized fun candidates(path: NetworkHandoff.Snapshot, servers: List<Server>, mask: (Server)->String,
        testUrl: String, now: Long): List<Server> {
        prune(now)
        return servers.filter { s -> entries[s.id]?.let {
            it.path == path && it.server == s && it.mask == mask(s) && it.testUrl == testUrl
        } == true }
    }
    @Synchronized fun remove(ids: Collection<String>) { ids.forEach { entries.remove(it) } }
    private fun prune(now: Long) { entries.entries.removeAll { now < it.value.at || now-it.value.at > TTL_MS } }
    companion object { const val CAPACITY=20; const val TTL_MS=60_000L; val session=WarmReserveMemory() }
}
