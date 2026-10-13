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
    private var attemptedAt = 0L

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

    /** One complete snapshot: absent operators must not retain obsolete evidence. */
    fun netForFamily(json: String, fam: String): Map<String, IntArray> = runCatching {
        JSONObject(json).optJSONObject(fam)?.let { parseNet(it.toString()) } ?: emptyMap()
    }.getOrDefault(emptyMap())

    fun cacheNet(ctx: Context, json: String, fam: String) {
        ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE).edit().putString("net_json", json).apply()
        net = netForFamily(json, fam); netFam = fam
    }

    fun loadNet(ctx: Context) {
        val fam = Net.family(Net.key(ctx))
        if (fam == netFam) return
        val p = ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE)
        net = p.getString("net_json", null)?.let { netForFamily(it, fam) }
            ?: p.getString("net:$fam", null)?.let { parseNet(it) } ?: emptyMap()
        netFam = fam
    }

    /** Other phones here saw it answer the ping but carry nothing, again and again: blocked on this operator. */
    fun blocked(s: Server, n: Map<String, IntArray> = net): Boolean = n[Report.serverKey(s)]?.let { it[2] >= 2 && it[1] < 25 } == true

    /** Best known speed: this operator's phones first (if it mostly works there), else the GitHub measurement. */
    fun speedOf(key: String, gh: Map<String, Int> = kbps, n: Map<String, IntArray> = net): Int =
        n[key]?.takeIf { it[0] > 0 && it[1] >= 50 }?.get(0) ?: gh[key] ?: 0

    /** Cloud changes every 10 min. Errors back off for a minute; saved data remain usable offline. */
    fun refreshDue(now: Long, fetched: Long, attempted: Long, hasCache: Boolean): Boolean =
        (!hasCache || now < fetched || now - fetched >= 10 * 60_000L) &&
            (attempted == 0L || now < attempted || now - attempted >= 60_000L)

    @Synchronized fun refresh(ctx: Context) {
        runCatching { loadNet(ctx) }
        val p = ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE)
        val cached = p.getString("json", null)
        if (kbps.isEmpty()) cached?.let { kbps = parse(it) }
        if (fetchedAt == 0L) fetchedAt = p.getLong("fetched", 0)
        val now = System.currentTimeMillis()
        if (!refreshDue(now, fetchedAt, attemptedAt, cached != null)) return
        attemptedAt = now
        runCatching {
            val c = java.net.URL(URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 2500; c.readTimeout = 2500
            if (cached != null) p.getString("etag", null)?.let { c.setRequestProperty("If-None-Match", it) }
            try {
                when (c.responseCode) {
                    304 -> if (cached != null) {
                        fetchedAt = now; p.edit().putLong("fetched", now).apply()
                    }
                    200 -> {
                        val text = c.inputStream.bufferedReader().use { it.readText() }
                        // Validate before saving the ETag: a bad response must not poison the cache.
                        JSONObject(text).getJSONObject("kbps")
                        kbps = parse(text); fetchedAt = now
                        p.edit().putString("json", text).putString("etag", c.getHeaderField("ETag"))
                            .putLong("fetched", now).apply()
                    }
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
