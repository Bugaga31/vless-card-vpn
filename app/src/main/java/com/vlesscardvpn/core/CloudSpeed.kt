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

    /** Fresh copy at most once an hour; offline → the saved one. Never throws, never waits more than ~4 s. */
    fun refresh(ctx: Context) {
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
    fun pick(pinged: List<Server>, skip: Collection<Server>, n: Int = 10, min: Int = 3000, speeds: Map<String, Int> = kbps): List<Server> {
        if (speeds.isEmpty()) return emptyList()
        val ids = skip.map { it.id }.toHashSet()
        return pinged.filter { it.id !in ids }.map { it to (speeds[Report.serverKey(it)] ?: 0) }
            .filter { it.second >= min }.sortedByDescending { it.second }.take(n).map { it.first }
    }
}
