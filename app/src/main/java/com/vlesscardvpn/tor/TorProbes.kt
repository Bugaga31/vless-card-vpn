package com.vlesscardvpn.tor

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Exit measurement for the rotator: a Cloudflare trace fetched through the local Tor SOCKS port.
 * Returns (latencyMs, exitCountry) — the same "measure latency per exit country" idea the rotator
 * used with its Cloudflare CDN ping, but tied to the real exit country (loc=) so the speed history
 * learns which countries are actually fast right now.
 */
object TorProbes {
    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    /**
     * Route health, not exit quality: any HTTP answer through Tor (a Cloudflare challenge page too) proves that
     * bridges, circuit and exit work. The request also gives an idle daemon a reason to build a circuit.
     */
    fun reachable(socksPort: Int = TorCard.DEFAULT_SOCKS_PORT, timeoutMs: Int = 10_000): Boolean {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false)
            .build()
        return try {
            client.newCall(Request.Builder().url(TRACE_URL).build()).execute().use { it.code in 100..599 }
        } catch (e: Exception) {
            false
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    /** [latencyMs, ISO country code] of the Tor exit, or null when the circuit is not working. */
    fun exitInfo(socksPort: Int = TorCard.DEFAULT_SOCKS_PORT, timeoutMs: Int = 8000): Pair<Int, String>? {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        return try {
            val t0 = System.nanoTime()
            client.newCall(Request.Builder().url(TRACE_URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val source = resp.body?.source() ?: return null
                source.request(8193)
                if (source.buffer.size > 8192) return null
                val body = source.readUtf8()
                val loc = body.lineSequence()
                    .firstOrNull { it.startsWith("loc=") }
                    ?.removePrefix("loc=")
                    ?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z]{2}")) }
                    ?: return null
                val latency = ((System.nanoTime() - t0) / 1_000_000L).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                latency to loc
            }
        } catch (e: Exception) {
            null
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
