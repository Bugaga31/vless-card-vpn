package com.vlesscardvpn.tor

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rotation brain absorbed from tor-rotator-vless (tor_rotator.py) with its documented
 * IMPROVEMENTS.md upgrades, now pure Kotlin: EMA speed history per exit country,
 * adaptive rotate thresholds with absolute floors, hysteresis blacklist with TTL decay.
 * Deterministic via the injected [now] clock — unit-testable without sleeps.
 */
class TorRotation(
    /** Rotation cadence floor, ms. */
    private val rotateEveryMs: Long = 10 * 60 * 1000,
    /** Blacklist TTL, ms; a country can be re-picked after this decay. */
    private val blacklistTtlMs: Long = 15 * 60 * 1000,
    /** History window per country. */
    private val historyMax: Int = 12,
    /** EMA smoothing factor for latency (0..1). */
    private val emaAlpha: Double = 0.3,
    /** Weight of the top-N best countries in the weighted pick. */
    private val topN: Int = 3,
    /** Latency ceiling used to demote unreliable exits, ms. */
    private val ceilingMs: Long = 7000,
    /** Absolute rotation threshold floor (never rotate below this), ms. */
    private val thresholdFloorMs: Long = 1500,
    /** Extra patience multiplier for slow-but-working transports like Snowflake. */
    private val snowflakeFactor: Double = 2.0,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Transport { DIRECT, OBFS4, SNOWFLAKE }

    data class Entry(val ema: Double, val lastSeenMs: Long, val samples: Int)

    private val history = HashMap<String, Entry>()
    private val blacklist = HashMap<String, Long>() // country -> blacklistedAt
    private var lastRotationAt = 0L

    /** Latency ceiling raised for slow transports (Snowflake is allowed 2x budget). */
    private fun ceilingMs(t: Transport): Long = when (t) {
        Transport.SNOWFLAKE -> (ceilingMs * snowflakeFactor).toLong()
        else -> ceilingMs
    }

    /**
     * Records a successful exit measurement. Returns true if the sample was accepted
     * (false when the latency exceeds the transport-aware ceiling — such an exit
     * gets blacklisted instead of polluting the history).
     */
    fun record(country: String, latencyMs: Long, transport: Transport = Transport.DIRECT, nowMs: Long = now()): Boolean {
        if (country.isBlank()) return false
        val lim = ceilingMs(transport)
        if (latencyMs > lim) {
            blacklist[country] = nowMs
            return false
        }
        val prev = history[country]
        val ema = if (prev == null) latencyMs.toDouble() else prev.ema + emaAlpha * (latencyMs - prev.ema)
        history[country] = Entry(ema, nowMs, (prev?.samples ?: 0) + 1)
        blacklist.remove(country)
        return true
    }

    fun blacklistCountry(country: String, nowMs: Long = now()) {
        blacklist[country] = nowMs
    }

    fun isBlacklisted(country: String, nowMs: Long = now()): Boolean {
        val at = blacklist[country] ?: return false
        if (nowMs - at >= blacklistTtlMs) {
            blacklist.remove(country)
            return false
        }
        return true
    }

    /** Countries eligible for picking: have history, not blacklisted (expired entries pruned). */
    fun candidates(nowMs: Long = now()): List<String> =
        history.keys.filter { !isBlacklisted(it, nowMs) }

    /**
     * Weighted pick biased to the fastest EMA, with exploration: a random candidate
     * wins with small probability so new/median exits keep getting measured.
     */
    fun pick(nowMs: Long = now()): String? {
        val cs = candidates(nowMs)
        if (cs.isEmpty()) return null
        val sorted = cs.sortedBy { history[it]?.ema ?: Double.MAX_VALUE }
        // a clear winner (≥2x faster than the next) is picked deterministically
        val best = history[sorted.first()]?.ema
        if (sorted.size > 1 && best != null && best * 2.0 < (history[sorted[1]]?.ema ?: best * 2.0)) return sorted.first()
        // exploration: 20% chance to try anything outside the top-N
        if (sorted.size > topN && Math.random() < 0.2) {
            return sorted.drop(topN).random()
        }
        // inverse-latency weights over the top-N
        val head = sorted.take(topN)
        val weights = head.map { 1.0 / (1.0 + (history[it]?.ema ?: ceilingMs.toDouble())) }
        val total = weights.sum()
        var x = Math.random() * total
        for ((i, w) in weights.withIndex()) {
            x -= w
            if (x <= 0) return head[i]
        }
        return head.last()
    }

    /** Adaptive rotate decision: cadence floor, absolute threshold floor, hysteresis vs the best alternative. */
    fun shouldRotate(nowMs: Long = now()): Boolean {
        if (nowMs - lastRotationAt < rotateEveryMs) return false
        val cur = lastCountry ?: return candidates(nowMs).isNotEmpty()
        val curEma = history[cur]?.ema ?: return candidates(nowMs).isNotEmpty()
        val best = candidates(nowMs).minByOrNull { history[it]?.ema ?: Double.MAX_VALUE } ?: return false
        if (best == cur) return false
        val bestEma = history[best]?.ema ?: Double.MAX_VALUE
        // rotate only if the alternative is meaningfully faster, and the gap clears the absolute floor
        if (bestEma >= curEma * 0.7) return false // <30% improvement: not worth a fresh-circuit stall
        return (curEma - bestEma) >= thresholdFloorMs
    }

    fun markRotated(nowMs: Long = now()) {
        lastRotationAt = nowMs
    }

    fun reset() {
        history.clear(); blacklist.clear(); lastRotationAt = 0L; lastCountry = null
    }

    fun setOnline(value: Boolean) {
        /* kept for API symmetry with TorRotator; policy is offline-agnostic */
    }

    var lastCountry: String? = null
        private set

    fun notePicked(country: String) {
        lastCountry = country
    }

    fun statsSnapshot(nowMs: Long = now()): JSONObject = JSONObject()
        .put("candidates", JSONArray(candidates(nowMs)))
        .put("blacklist", JSONArray(blacklist.keys))
        .put("history", JSONObject().apply { history.forEach { (k, v) -> put(k, v.ema) } })
        .put("lastRotationAt", lastRotationAt)

    fun toJson(nowMs: Long = now()): String = JSONObject()
        .put("history", JSONObject().apply { history.forEach { (k, v) -> put(k, JSONObject().put("ema", v.ema).put("lastSeenMs", v.lastSeenMs).put("samples", v.samples)) } })
        .put("blacklist", JSONObject().apply { blacklist.forEach { (k, v) -> put(k, v) } })
        .put("lastRotationAt", lastRotationAt)
        .put("lastCountry", lastCountry ?: JSONObject.NULL)
        .toString()

    fun restoreJson(json: String, nowMs: Long = now()) {
        val j = JSONObject(json)
        history.clear(); blacklist.clear()
        val h = j.optJSONObject("history") ?: JSONObject()
        h.keys().forEachRemaining { k ->
            val e = h.getJSONObject(k)
            history[k] = Entry(e.getDouble("ema"), e.getLong("lastSeenMs"), e.getInt("samples"))
        }
        val b = j.optJSONObject("blacklist") ?: JSONObject()
        b.keys().forEachRemaining { k -> blacklist[k] = b.getLong(k) }
        lastRotationAt = j.optLong("lastRotationAt", 0L)
        lastCountry = if (j.isNull("lastCountry")) null else j.optString("lastCountry").ifEmpty { null }
    }
}
