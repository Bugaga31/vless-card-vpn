package com.vlesscardvpn.core

/** Short, network+mask-scoped soft cooldown. No persistent blacklist or phone data upload. */
class ProbeMemory(private val ttlMs: Long = 60_000L, private val capacity: Int = 512) {
    private val failed = linkedMapOf<Triple<String, String, String>, Long>()
    @Synchronized fun record(net: String, id: String, mask: String, ok: Boolean, now: Long) {
        val key = Triple(net, id, mask)
        if (ok) failed.remove(key) else { failed.remove(key); failed[key] = now }
        while (failed.size > capacity.coerceAtLeast(1)) failed.remove(failed.keys.first())
    }
    @Synchronized fun cooling(net: String, id: String, mask: String, now: Long): Boolean {
        val key = Triple(net, id, mask); val at = failed[key] ?: return false
        if (now < at || now - at >= ttlMs) { failed.remove(key); return false }
        return true
    }
}
