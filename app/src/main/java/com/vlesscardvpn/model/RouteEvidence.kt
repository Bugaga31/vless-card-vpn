package com.vlesscardvpn.model

import org.json.JSONObject
import kotlin.math.abs

/** Phone observations only; cloud suggestions must never fabricate these counters. */
data class RouteEvidence(val checks: Int = 0, val successes: Int = 0, val ema: Double = .5,
                         val latency: Double = 0.0, val jitter: Double = 0.0, val streak: Int = 0, val at: Long = 0) {
    fun observe(ok: Boolean, ms: Int, now: Long): RouteEvidence {
        val sample = if (ok) 1.0 else 0.0
        return copy(checks = (checks + 1).coerceAtMost(1000000), successes = (successes + if (ok) 1 else 0).coerceAtMost(1000000),
            ema = ema * .65 + sample * .35,
            latency = if (!ok) latency else if (latency <= 0) ms.toDouble() else latency * .65 + ms * .35,
            jitter = if (!ok || latency <= 0) jitter else jitter * .65 + abs(ms - latency) * .35,
            streak = if (ok) 0 else (streak + 1).coerceAtMost(3), at = now)
    }
    fun toJson() = JSONObject().put("n", checks).put("ok", successes).put("e", ema).put("ms", latency).put("j", jitter).put("f", streak).put("at", at)
    companion object {
        fun parse(o: JSONObject): RouteEvidence? = runCatching {
            val v = RouteEvidence(o.getInt("n"), o.getInt("ok"), o.getDouble("e"), o.getDouble("ms"), o.getDouble("j"), o.getInt("f"), o.getLong("at"))
            require(v.checks in 0..1000000 && v.successes in 0..v.checks && v.ema.isFinite() && v.ema in 0.0..1.0)
            require(v.latency.isFinite() && v.latency in 0.0..60000.0 && v.jitter.isFinite() && v.jitter in 0.0..60000.0 && v.streak in 0..3 && v.at >= 0)
            v
        }.getOrNull()
    }
}
