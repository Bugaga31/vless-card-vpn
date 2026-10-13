package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

/** Pure policies used by automatic probing and ranking; no downloads or extra background work. */
object ServerChoice {
    /** Try different hosts before spending the whole test budget on many links to one machine.
     * Ranked order is preserved within each pass; remaining transport variants fill unused slots.
     */
    fun diverse(ranked: List<Server>, limit: Int): List<Server> {
        if (limit <= 0) return emptyList()
        val hosts = HashSet<String>()
        val first = ArrayList<Server>(); val rest = ArrayList<Server>()
        ranked.distinctBy { it.id }.forEach { s ->
            val host = s.address.lowercase(java.util.Locale.ROOT).removeSuffix(".").removeSurrounding("[", "]")
            if (hosts.add(host)) first += s else rest += s
        }
        return (first + rest).take(limit)
    }

    /** Discount a cloud opinion with little evidence instead of switching abruptly after two checks. */
    fun evidence(checks: Int): Double = checks.coerceAtLeast(0).toDouble() / (checks.coerceAtLeast(0).toDouble() + 6.0)

    /** Beta(2,2) prior: unknown is 50%; a few failures alone cannot blacklist a server. */
    fun successChance(percent: Int, checks: Int): Double {
        val n = checks.coerceAtLeast(0).toDouble()
        return (percent.coerceIn(0, 100) / 100.0 * n + 2.0) / (n + 4.0)
    }

    /** Small hysteresis for a working server already in use. A clearly faster spare still wins. */
    fun stayFactor(selected: Boolean): Double = if (selected) 0.92 else 1.0
}
