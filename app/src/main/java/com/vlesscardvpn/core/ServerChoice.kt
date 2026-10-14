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
    /** Gradual Bayesian warm start, exact-network local evidence wins as genuine successes accumulate. */
    fun blended(cloud: Double?, local: com.vlesscardvpn.model.RouteEvidence, now: Long): Double {
        val freshness = if (local.at <= 0 || now < local.at) 0.0 else kotlin.math.exp(-(now - local.at) / 86_400_000.0)
        val alpha = (.85 * local.successes.coerceIn(0, 5) / 5.0) * freshness
        val prior = cloud?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: .5
        return ((1 - alpha) * prior + alpha * local.ema - local.latency.coerceAtLeast(0.0) / 10000 - local.jitter.coerceAtLeast(0.0) / 20000).coerceIn(0.0, 1.0)
    }
    /** Latency-mode estimate; no claim of UDP loss measurement. Unknown evidence gets a conservative penalty. */
    fun latencyScore(local: com.vlesscardvpn.model.RouteEvidence, fallbackMs: Int): Double =
        (if (local.latency > 0) local.latency else fallbackMs.takeIf { it > 0 }?.toDouble() ?: 3000.0) +
            2 * local.jitter.coerceAtLeast(0.0) + (1 - local.ema.coerceIn(0.0,1.0)) * 1000 +
            (if (local.checks < 2) 200 else 0)
    /** Honest upper-confidence exploration bonus; use on background candidates, not forced live switches. */
    fun exploration(checks: Int, total: Int): Double = kotlin.math.sqrt(2 * kotlin.math.ln((total.coerceAtLeast(0) + 2).toDouble()) / (checks.coerceAtLeast(0) + 1))

}
