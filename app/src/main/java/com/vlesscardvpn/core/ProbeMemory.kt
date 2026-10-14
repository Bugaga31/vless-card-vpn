package com.vlesscardvpn.core

/** Progressive, network+mask-scoped SOFT cooldown. The last candidate is never permanently banned. */
class ProbeMemory(private val ttlMs: Long = 120_000L, private val capacity: Int = 512) {
    private data class Failure(val at: Long, val streak: Int)
    private val failed = linkedMapOf<Triple<String, String, String>, Failure>()
    companion object { fun cooldownMs(streak: Int): Long = when { streak <= 1 -> 120_000L; streak == 2 -> 900_000L; else -> 7_200_000L } }
    @Synchronized fun record(net: String, id: String, mask: String, ok: Boolean, now: Long) {
        val key = Triple(net, id, mask)
        if (ok) failed.remove(key) else {
            val old = failed.remove(key)
            val streak = if (old != null && now >= old.at && now - old.at < 86_400_000L) (old.streak + 1).coerceAtMost(3) else 1
            failed[key] = Failure(now, streak)
        }
        while (failed.size > capacity.coerceAtLeast(1)) failed.remove(failed.keys.first())
    }
    @Synchronized fun cooling(net: String, id: String, mask: String, now: Long): Boolean {
        val key = Triple(net, id, mask); val f = failed[key] ?: return false
        if (now < f.at) { failed.remove(key); return false }
        val duration = if (ttlMs == 120_000L) cooldownMs(f.streak) else ttlMs
        return now - f.at < duration
    }
}
