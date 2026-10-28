package com.vlesscardvpn.core

/** Bounded session-only failure memory. Keys never leave the device or mix networks/masks. */
object TelegramRouteMemory {
    private data class Failure(val at: Long, val streak: Int)
    private val failures = linkedMapOf<Triple<String,String,String>,Failure>()
    @Synchronized fun record(net: String, id: String, mask: String, result: Boolean?, now: Long) {
        if (result == null) return
        val key = Triple(net,id,mask)
        if (result) { failures.remove(key); return }
        val old = failures.remove(key)
        val streak = if (old != null && now >= old.at && now-old.at <= 30*60000L) (old.streak+1).coerceAtMost(5) else 1
        failures[key] = Failure(now,streak)
        while (failures.size > 512) failures.remove(failures.keys.first())
    }
    @Synchronized fun cooling(net: String, id: String, mask: String, now: Long): Boolean {
        val key = Triple(net,id,mask)
        val f = failures[key] ?: return false
        if (now < f.at) { failures.remove(key); return false }
        val duration = minOf(300000L,15000L * (1L shl (f.streak-1)))
        return now-f.at < duration
    }
}
