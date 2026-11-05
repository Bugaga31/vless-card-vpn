package com.vlesscardvpn.tor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Tor Project's own bridge distribution: the «moat» circumvention API behind Tor Browser's Connection Assist.
 * For a censored country it hands out fresh obfs4/WebTunnel bridges that Tor Project tested, without a CAPTCHA.
 * Asked only through the running VPN: `bridges.torproject.org` in a TLS handshake must never leave the phone on
 * the bare network. The reply is untrusted like any list: every line goes through [TorBridgeCatalog.normalize].
 */
object TorMoat {
    const val SETTINGS = "https://bridges.torproject.org/moat/circumvention/settings"
    const val COUNTRY = "ru"
    const val MAX_BYTES = 65536
    /** Lines of one transport kept between refreshes (the source hands out a couple per request). */
    const val KEEP_MAX = 8

    /** The app's transports only: snowflake/meek do not run over the VLESS upstream. */
    fun request(country: String = COUNTRY): String =
        JSONObject().put("country", country).put("transports", JSONArray(listOf("obfs4", "webtunnel"))).toString()

    /** Valid obfs4/WebTunnel lines of a settings reply; built-in transports, errors and malformed entries are dropped. */
    fun parse(body: String): TorBridgeCatalog.Parsed {
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Moat reply too large" }
        val settings = JSONObject(body).optJSONArray("settings") ?: return TorBridgeCatalog.Parsed(emptyList(), 0)
        val lines = linkedSetOf<String>()
        var rejected = 0
        for (i in 0 until minOf(settings.length(), 16)) {
            val bridges = settings.optJSONObject(i)?.optJSONObject("bridges") ?: continue
            val type = bridges.optString("type")
            if (type !in setOf("obfs4", "webtunnel")) continue
            val strings = bridges.optJSONArray("bridge_strings") ?: continue
            for (j in 0 until minOf(strings.length(), 32)) {
                val line = (strings.opt(j) as? String)?.let(TorBridgeCatalog::normalize)
                if (line != null && line.startsWith("Bridge $type ")) lines += line else rejected++
            }
        }
        return TorBridgeCatalog.Parsed(lines.toList(), rejected)
    }

    /**
     * Newest bridges first, then the ones kept from earlier answers, at most [KEEP_MAX] per transport: a source that
     * is down this time still leaves its last bridges in the catalog.
     */
    fun merge(fresh: List<String>, kept: List<String>): List<String> = listOf("obfs4", "webtunnel").flatMap { t ->
        (fresh + kept).filter { it.startsWith("Bridge $t ") }.distinctBy { TorBridgeMemory.fingerprint(it) ?: it }.take(KEEP_MAX)
    }

    /** [socksPort]: the running tunnel's loopback SOCKS — required, there is no direct path. */
    suspend fun fetch(socksPort: Int, timeoutSec: Long = 6): TorBridgeCatalog.Parsed = withContext(Dispatchers.IO) {
        require(socksPort in 1..65535)
        val client = OkHttpClient.Builder().callTimeout(timeoutSec, TimeUnit.SECONDS).connectTimeout(minOf(4, timeoutSec), TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)
            .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", socksPort))).build()
        try {
            val call = Request.Builder().url(SETTINGS).header("Accept", "application/vnd.api+json")
                .post(request().toRequestBody("application/vnd.api+json".toMediaType())).build()
            client.newCall(call).execute().use { r ->
                check(r.code == 200) { "Moat HTTP ${r.code}" }
                val input = r.body?.byteStream() ?: error("Empty moat reply")
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    check(out.size() + n <= MAX_BYTES) { "Moat reply too large" }; out.write(buf, 0, n)
                }
                parse(out.toString("UTF-8"))
            }
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
