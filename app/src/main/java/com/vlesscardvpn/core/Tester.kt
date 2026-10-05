package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.XrayConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

data class Probe(val realMs: Int, val bigOk: Boolean?, val ytOk: Boolean?, val error: String = "") {
    val works: Boolean get() = realMs > 0 && bigOk != false
}

/**
 * Honest connectivity tests, run from the phone outside the VPN:
 *  - TCP: connect time to server:port;
 *  - real: HTTPS request through Xray with the server's masking (like v2rayNG "real delay");
 *  - big: 256 KB download through the server — catches the TSPU "16 KB freeze" (connects, then hangs);
 *  - YouTube: generate_204 on youtube.com through the server.
 * Many servers/masks are tested at once through one Xray instance (one SOCKS port per variant).
 */
object Tester {
    private val mutex = Mutex()
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    }
    val BIG_URLS = listOf("https://speed.cloudflare.com/__down?bytes=262144", "https://cachefly.cachefly.net/1mb.test")
    const val YT_URL = "https://www.youtube.com/generate_204"
    const val BIG_BYTES = 200_000

    suspend fun tcp(servers: List<Server>, parallel: Int = 48, onEach: (Server, Int) -> Unit) = coroutineScope {
        val sem = Semaphore(parallel)
        servers.map { s -> async(Dispatchers.IO) { sem.withPermit { onEach(s, if (s.isTcpBased) tcpOne(s.address, s.port) else resolves(s.address)) } } }.awaitAll()
    }

    /** ms (≥1) or 0 when unreachable. UDP-only protocols (Hysteria2) return 1 if the name resolves. */
    fun tcpOne(host: String, port: Int, timeoutMs: Int = 3000): Int = runCatching {
        val addr = InetAddress.getByName(host)
        val t0 = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) }
        ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1)
    }.getOrDefault(0)

    private fun resolves(host: String): Int = if (runCatching { InetAddress.getByName(host) }.isSuccess) 1 else 0

    private fun freePorts(n: Int): List<Int> {
        val sockets = (0 until n).map { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
        return sockets.map { it.localPort }.also { sockets.forEach { s -> s.close() } }
    }

    /** Requests through a local SOCKS port (the tester's or the running VPN's). */
    fun probeSocks(port: Int, testUrl: String, big: Boolean = true, youtube: Boolean = true): Probe {
        val client = base.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).build()
        var best = Long.MAX_VALUE; var err = ""
        repeat(2) {
            val t0 = System.nanoTime()
            runCatching {
                client.newCall(Request.Builder().url(testUrl).header("User-Agent", UA).build()).execute().use { r ->
                    check(r.code in 200..399) { "HTTP ${r.code}" }
                    r.body?.bytes()
                }
                best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
            }.onFailure { err = it.message ?: it.javaClass.simpleName }
        }
        if (best == Long.MAX_VALUE) return Probe(0, null, null, err)
        val bigOk = if (!big) null else BIG_URLS.any { url -> runCatching { download(client, url) >= BIG_BYTES }.getOrDefault(false) }
        val ytOk = if (!youtube) null else runCatching {
            client.newCall(Request.Builder().url(YT_URL).header("User-Agent", UA).build()).execute().use { it.code in 200..399 }
        }.getOrDefault(false)
        client.connectionPool.evictAll()
        return Probe(best.toInt().coerceAtLeast(1), bigOk, ytOk)
    }

    private fun download(client: OkHttpClient, url: String): Int =
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            check(r.isSuccessful); val src = r.body!!.byteStream(); val buf = ByteArray(16384); var total = 0
            while (total < BIG_BYTES) { val n = src.read(buf); if (n < 0) break; total += n }
            total
        }

    /** Tests [variants] (server + mask) in parallel through one temporary Xray instance. */
    suspend fun real(
        variants: List<Pair<Server, Mask?>>, testUrl: String, byeDpiPort: Int?, youtube: Boolean = true,
        batch: Int = 32, parallel: Int = 12, onEach: (Int, Probe) -> Unit,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            variants.chunked(batch).forEachIndexed { chunkNo, chunk ->
                val ports = freePorts(chunk.size)
                val core = XrayCore.Instance("test")
                val started = runCatching { core.start(XrayConfigBuilder.testConfig(chunk, ports, byeDpiPort)) }
                if (started.isFailure || !core.running) {
                    // A broken link must not hide the others: fall back to one instance per variant.
                    core.stop()
                    chunk.forEachIndexed { i, v ->
                        val one = XrayCore.Instance("test1")
                        val p = freePorts(1)
                        val ok = runCatching { one.start(XrayConfigBuilder.testConfig(listOf(v), p, byeDpiPort)) }.isSuccess && one.running
                        onEach(chunkNo * batch + i, if (ok) probeSocks(p[0], testUrl, youtube = youtube) else Probe(0, null, null, "конфиг не принят ядром"))
                        one.stop()
                    }
                    return@forEachIndexed
                }
                try {
                    coroutineScope {
                        val sem = Semaphore(parallel)
                        chunk.indices.map { i ->
                            async { sem.withPermit { onEach(chunkNo * batch + i, probeSocks(ports[i], testUrl, youtube = youtube)) } }
                        }.awaitAll()
                    }
                } finally { core.stop() }
            }
        }
    }

    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
}
