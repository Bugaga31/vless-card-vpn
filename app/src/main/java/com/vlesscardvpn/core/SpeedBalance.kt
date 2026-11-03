package com.vlesscardvpn.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow

/** Xray leastLoad with ordinary observatory: RTT * sqrt(cost), normalized to measured throughput.
 * All configured routes keep live health probes. Only current-network isolated samples are passed here.
 * No bandwidth bonding; existing TCP streams are not migrated between servers.
 */
object SpeedBalance {
    fun costs(speeds: List<Int>, latencies: List<Int>): List<Double> {
        val best = speeds.maxOrNull()?.coerceAtLeast(1)?.toDouble() ?: 1.0
        return speeds.mapIndexed { i, kbps ->
            val relative = if (kbps > 0) best / kbps else 16.0
            val baseline = latencies.getOrNull(i)?.takeIf { it > 0 }?.coerceAtMost(60000)?.toDouble() ?: 100.0
            (100.0 * relative / baseline).pow(2).coerceIn(1e-8,1e12)
        }
    }
    fun fingerprint(speeds: List<Int>, latencies: List<Int>): String =
        if (speeds.any { it > 0 }) strategy(speeds,latencies).toString() else ""

    fun strategy(speeds: List<Int>, latencies: List<Int>): JSONObject {
        return JSONObject().put("type","leastLoad").put("settings",JSONObject()
            .put("expected",1).put("maxRTT","4s").put("tolerance",0.0)
            .put("costs",JSONArray(costs(speeds,latencies).mapIndexed { i,v ->
                JSONObject().put("regexp",true).put("match","^proxy-$i$").put("value",v)
            })))
    }
}
