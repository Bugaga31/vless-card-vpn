package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.model.Server
import org.json.JSONObject

/**
 * Real download speed of servers measured by the cloud (GitHub, every 10 min): `servers/speed.json`,
 * server key ([Report.serverKey]) → kbit/s. «Авто» tests the cloud-fast ones next to the nearest by ping,
 * so a fast server a bit farther away gets its chance; the phone's own test still decides.
 */
object CloudSpeed {
    const val URL = "https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/speed.json"
    @Volatile var kbps: Map<String, Int> = emptyMap()
    private var fetchedAt = 0L

    fun parse(json: String): Map<String, Int> = runCatching {
        JSONObject(json).getJSONObject("kbps").let { o -> o.keys().asSequence().associateWith { o.optInt(it) }.filterValues { it > 0 } }
    }.getOrDefault(emptyMap())

    /**
     * From phones on the current operator (cloud.json "net"): server key → [kbit/s, % of checks that worked, checks].
     * Fresher and more relevant than the GitHub speed: a server fast in a datacenter may be blocked on mobile.
     */
    @Volatile var net: Map<String, IntArray> = emptyMap()
    @Volatile private var netFam = ""

    fun parseNet(json: String): Map<String, IntArray> = runCatching {
        JSONObject(json).let { o -> o.keys().asSequence().mapNotNull { k -> o.optJSONArray(k)?.takeIf { it.length() >= 3 }
            ?.let { a -> k to intArrayOf(a.optInt(0), a.optInt(1), a.optInt(2)) } }.toMap() }
    }.getOrDefault(emptyMap())

    fun setNet(ctx: Context, fam: String, json: String) {
        ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE).edit().putString("net:$fam", json).apply()
        net = parseNet(json); netFam = fam
    }

    private fun loadNet(ctx: Context) {
        val fam = Net.family(Net.key(ctx))
        if (fam == netFam) return
        net = ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE).getString("net:$fam", null)?.let { parseNet(it) } ?: emptyMap()
        netFam = fam
    }

    /** Other phones here saw it answer the ping but carry nothing, again and again: blocked on this operator. */
    fun blocked(s: Server, n: Map<String, IntArray> = net): Boolean = n[Report.serverKey(s)]?.let { it[2] >= 2 && it[1] < 25 } == true

    /** Best known speed: this operator's phones first (if it mostly works there), else the GitHub measurement. */
    fun speedOf(key: String, gh: Map<String, Int> = kbps, n: Map<String, IntArray> = net): Int =
        n[key]?.takeIf { it[0] > 0 && it[1] >= 50 }?.get(0) ?: gh[key] ?: 0

    /** Fresh copy at most once an hour; offline → the saved one. Never throws, never waits more than ~4 s. */
    fun refresh(ctx: Context) {
        runCatching { loadNet(ctx) }
        val p = ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE)
        if (kbps.isEmpty()) p.getString("json", null)?.let { kbps = parse(it) }
        if (System.currentTimeMillis() - fetchedAt < 3600_000L && kbps.isNotEmpty()) return
        runCatching {
            val c = java.net.URL(URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 2500; c.readTimeout = 2500
            try {
                if (c.responseCode == 200) {
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    val m = parse(text)
                    if (m.isNotEmpty()) { kbps = m; fetchedAt = System.currentTimeMillis(); p.edit().putString("json", text).apply() }
                }
            } finally { c.disconnect() }
        }
    }

    /** Up to [n] servers from [pinged] (not in [skip]) the cloud saw download ≥ [min] kbit/s, fastest first. */
    fun pick(pinged: List<Server>, skip: Collection<Server>, n: Int = 10, min: Int = 3000, speeds: Map<String, Int> = kbps,
             here: Map<String, IntArray> = net): List<Server> {
        if (speeds.isEmpty() && here.isEmpty()) return emptyList()
        val ids = skip.map { it.id }.toHashSet()
        return pinged.filter { it.id !in ids && !blocked(it, here) }.map { it to speedOf(Report.serverKey(it), speeds, here) }
            .filter { it.second >= min }.sortedByDescending { it.second }.take(n).map { it.first }
    }
}
