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
import java.net.Authenticator
import java.net.InetAddress
import java.net.PasswordAuthentication
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

data class Probe(val realMs: Int, val bigOk: Boolean?, val ytOk: Boolean?, val error: String = "", val tgOk: Boolean? = null, val kbps: Int = 0) {
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
    val SKIPPED = Probe(0, null, null, "пропущено")
    private val mutex = Mutex()
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    }
    /** Searches (many variants): one attempt and shorter timeouts — a mask that needs 8 s is useless anyway. */
    private val fast: OkHttpClient by lazy {
        base.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build()
    }
    val BIG_URLS = listOf("https://speed.cloudflare.com/__down?bytes=262144", "https://cachefly.cachefly.net/1mb.test")
    const val YT_URL = "https://www.youtube.com/generate_204"
    const val TG_URL = "https://api.telegram.org/"
    const val BIG_BYTES = 200_000

    /**
     * TCP ping of all servers at once: non-blocking sockets in one selector (no thread per server — the 96-thread pool
     * used to cap it), names resolved in parallel first (each distinct name once). Results arrive as they come.
     */
    suspend fun tcp(servers: List<Server>, parallel: Int = 96, timeoutMs: Int = 2500, onEach: (Server, Int) -> Unit) = coroutineScope {
        val names = servers.map { it.address }.distinct()
        val sem = Semaphore(64)
        val ips = names.map { h -> async(Bg.io) { sem.withPermit { h to runCatching { InetAddress.getByName(h) }.getOrNull() } } }.awaitAll().toMap()
        servers.filter { !it.isTcpBased }.forEach { onEach(it, if (ips[it.address] != null) 1 else 0) }
        val tcpList = servers.filter { it.isTcpBased }
        tcpList.filter { ips[it.address] == null }.forEach { onEach(it, 0) }
        val ok = tcpList.filter { ips[it.address] != null }
        kotlinx.coroutines.withContext(Bg.io) { nioPing(ok.map { InetSocketAddress(ips.getValue(it.address), it.port) }, timeoutMs, maxOf(parallel, 512)) { i, ms -> onEach(ok[i], ms) } }
    }

    /** Connects to all [targets] with up to [window] sockets open at once; [onEach] (index, ms or 0). Blocking. */
    fun nioPing(targets: List<InetSocketAddress>, timeoutMs: Int, window: Int = 512, onEach: (Int, Int) -> Unit) {
        if (targets.isEmpty()) return
        val sel = java.nio.channels.Selector.open()
        val start = LongArray(targets.size); val open = HashMap<Int, java.nio.channels.SocketChannel>()
        var next = 0
        fun finish(i: Int, ms: Int) { open.remove(i)?.let { ch -> runCatching { ch.close() } }; runCatching { onEach(i, ms) } }
        try {
            while (next < targets.size || open.isNotEmpty()) {
                while (next < targets.size && open.size < window) {
                    val i = next++
                    val ch = runCatching { java.nio.channels.SocketChannel.open().apply { configureBlocking(false) } }.getOrNull()
                    if (ch == null) { onEach(i, 0); continue }
                    start[i] = System.nanoTime(); open[i] = ch
                    val now = runCatching { ch.connect(targets[i]) }
                    when {
                        now.isFailure -> finish(i, 0)
                        now.getOrDefault(false) -> finish(i, 1)
                        else -> ch.register(sel, java.nio.channels.SelectionKey.OP_CONNECT, i)
                    }
                }
                sel.select(20)
                val it = sel.selectedKeys().iterator()
                while (it.hasNext()) {
                    val k = it.next(); it.remove()
                    val i = k.attachment() as Int; k.cancel()
                    val done = runCatching { (k.channel() as java.nio.channels.SocketChannel).finishConnect() }.getOrDefault(false)
                    finish(i, if (done) ((System.nanoTime() - start[i]) / 1_000_000).toInt().coerceAtLeast(1) else 0)
                }
                val limit = System.nanoTime() - timeoutMs * 1_000_000L
                open.keys.filter { start[it] < limit }.forEach { finish(it, 0) }
            }
        } finally { open.values.forEach { runCatching { it.close() } }; runCatching { sel.close() } }
    }

    /** ms (≥1) or 0 when unreachable. UDP-only protocols (Hysteria2) return 1 if the name resolves. */
    fun tcpOne(host: String, port: Int, timeoutMs: Int = 2500): Int = runCatching {
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
    fun probeSocks(port: Int, testUrl: String, big: Boolean = true, youtube: Boolean = true, attempts: Int = 2): Probe {
        val client = (if (attempts == 1) fast else base).newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).build()
        var best = Long.MAX_VALUE; var err = ""
        repeat(attempts) {
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
        // big file, YouTube and Telegram at the same time (they used to run one after another: up to 4× longer per server)
        val bigF = if (big) pool.submit<Pair<Boolean, Int>> { bigDownload(client) } else null
        val ytF = if (youtube) pool.submit<Boolean> { runCatching {
            client.newCall(Request.Builder().url(YT_URL).header("User-Agent", UA).build()).execute().use { it.code in 200..399 } }.getOrDefault(false) } else null
        val tgF = if (youtube) pool.submit<Boolean> { runCatching {
            client.newCall(Request.Builder().url(TG_URL).header("User-Agent", UA).build()).execute().use { it.code in 200..499 } }.getOrDefault(false) } else null
        val (bigOk, kbps) = bigF?.let { f -> runCatching { f.get(40, TimeUnit.SECONDS) }.getOrDefault(false to 0) }?.let { it.first to it.second } ?: (null to 0)
        val ytOk = ytF?.let { f -> runCatching { f.get(40, TimeUnit.SECONDS) }.getOrDefault(false) }
        val tgOk = tgF?.let { f -> runCatching { f.get(40, TimeUnit.SECONDS) }.getOrDefault(false) }
        client.connectionPool.evictAll()
        return Probe(best.toInt().coerceAtLeast(1), bigOk, ytOk, tgOk = tgOk, kbps = kbps)
    }

    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "probe").apply { isDaemon = true } }

    /** 256 KB through the server: (passed, kbit/s). A freeze (read timeout — the TSPU "16 KB" cut) fails at once, no second mirror. */
    private fun bigDownload(client: OkHttpClient): Pair<Boolean, Int> {
        for (url in BIG_URLS) {
            val t0 = System.nanoTime()
            try {
                val n = download(client, url)
                if (n >= BIG_BYTES) return true to (n * 8L * 1_000_000 / ((System.nanoTime() - t0) / 1000).coerceAtLeast(1) / 1000).toInt().coerceAtLeast(1)
            } catch (e: java.io.InterruptedIOException) { return false to 0 } catch (e: Exception) { /* mirror down: next */ }
        }
        return false to 0
    }

    /** Cloudflare trace through the running tunnel: "on" / "plus" when traffic really goes through WARP, "off" otherwise, null = no answer. */
    fun warpTrace(port: Int): String? = runCatching {
        val client = fast.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).build()
        client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").header("User-Agent", UA).build()).execute().use { r ->
            r.body?.string().orEmpty().lineSequence().firstOrNull { it.startsWith("warp=") }?.substringAfter('=')?.trim()
        }
    }.getOrNull()

    /** Country (ISO) the internet sees through the tunnel — Cloudflare trace «loc=». null = no answer. */
    fun exitCountry(port: Int): String? = runCatching {
        val client = fast.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).build()
        client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").header("User-Agent", UA).build()).execute().use { r ->
            traceLoc(r.body?.string().orEmpty())
        }
    }.getOrNull()
    fun traceLoc(body: String): String? = body.lineSequence().firstOrNull { it.startsWith("loc=") }?.substringAfter('=')?.trim()?.uppercase()?.takeIf { it.length == 2 }

    data class SpeedResult(val mbps: Double, val pingMs: Int, val bytes: Long)
    val SPEED_URLS = listOf("https://speed.cloudflare.com/__down?bytes=25000000", "https://cachefly.cachefly.net/10mb.test")

    /** Download speed through the running tunnel: up to [seconds] s of one big file (first mirror that answers), plus ping. */
    fun speed(port: Int, seconds: Int = 8): SpeedResult {
        val client = base.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .callTimeout(seconds + 10L, TimeUnit.SECONDS).build()
        val ping = runCatching {
            val t0 = System.nanoTime()
            client.newCall(Request.Builder().url("https://www.gstatic.com/generate_204").header("User-Agent", UA).build()).execute().use { }
            ((System.nanoTime() - t0) / 1_000_000).toInt()
        }.getOrDefault(0)
        for (url in SPEED_URLS) {
            val r = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { resp ->
                    check(resp.isSuccessful)
                    val src = resp.body!!.byteStream(); val buf = ByteArray(65536); var total = 0L
                    val t0 = System.nanoTime(); val end = t0 + seconds * 1_000_000_000L
                    while (System.nanoTime() < end) { val n = src.read(buf); if (n < 0) break; total += n }
                    val sec = (System.nanoTime() - t0) / 1e9
                    SpeedResult(if (sec > 0) total * 8 / sec / 1e6 else 0.0, ping, total)
                }
            }.getOrNull()
            if (r != null && r.bytes > 100_000) { client.connectionPool.evictAll(); return r }
        }
        client.connectionPool.evictAll()
        return SpeedResult(0.0, ping, 0)
    }

    /** One site check: "ok" (any HTTP answer with a body start), "denied" (403/451: refuses this country/IP), "timeout" (connects, then hangs — DPI), "reset", "dns", "tls", "fail". */
    data class SiteProbe(val result: String, val ms: Int = 0, val code: Int = 0) { val ok get() = result == "ok"; val answers get() = ok || result == "denied" }
    fun site(url: String, socksPort: Int?): SiteProbe {
        val b = fast.newBuilder().followRedirects(true)
        if (socksPort != null) b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        val client = b.build(); val t0 = System.nanoTime()
        return try {
            client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                // a page that starts loading: DPI "16 KB freeze" shows up as a read timeout here
                r.body?.byteStream()?.let { src -> val buf = ByteArray(16384); var t = 0; while (t < 40_000) { val n = src.read(buf); if (n < 0) break; t += n } }
                SiteProbe(if (r.code == 403 || r.code == 451) "denied" else "ok", ((System.nanoTime() - t0) / 1_000_000).toInt(), r.code)
            }
        } catch (e: Exception) {
            SiteProbe(when (e) {
                is java.net.UnknownHostException -> "dns"
                is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "timeout"
                is javax.net.ssl.SSLException -> if ((e.message ?: "").contains("reset", true)) "reset" else "tls"
                is java.net.SocketException -> if ((e.message ?: "").contains("reset", true)) "reset" else "fail"
                else -> "fail"
            })
        } finally { client.connectionPool.evictAll() }
    }

    /** Real download speed (kbit/s, 0 = failed) of several variants, one after another so they don't share the line. */
    suspend fun speedMany(variants: List<Pair<Server, Mask?>>, byeDpiPort: Int?, dpiPorts: Map<String, Int>, seconds: Int = 3): List<Int> = mutex.withLock {
        withContext(Bg.io) {
            val ports = freePorts(variants.size)
            val core = XrayCore.Instance("speed")
            try {
                runCatching { core.start(XrayConfigBuilder.testConfig(variants, ports, byeDpiPort, dpiPorts)) }
                if (!core.running) return@withContext variants.map { 0 }
                ports.map { p -> runCatching { (speed(p, seconds).mbps * 1000).toInt() }.getOrDefault(0) }
            } finally { core.stop() }
        }
    }

    private fun download(client: OkHttpClient, url: String): Int =
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            check(r.isSuccessful); val src = r.body!!.byteStream(); val buf = ByteArray(16384); var total = 0
            while (total < BIG_BYTES) { val n = src.read(buf); if (n < 0) break; total += n }
            total
        }

    /** Tests [variants] (server + mask) in parallel through one temporary Xray instance. */
    suspend fun real(
        variants: List<Pair<Server, Mask?>>, testUrl: String, byeDpiPort: Int?, dpiPorts: Map<String, Int> = emptyMap(), youtube: Boolean = true,
        batch: Int = 32, parallel: Int = 12, attempts: Int = 2, big: Boolean = true,
        /** Early stop: variants for which this returns true are not probed (reported as skipped). */
        skip: (Int) -> Boolean = { false },
        onEach: (Int, Probe) -> Unit,
    ) = mutex.withLock {
        withContext(Bg.io) {
            for (all in chunks(variants, batch)) {
                val idx = all.filter { i -> if (skip(i)) { onEach(i, SKIPPED); false } else true }
                if (idx.isEmpty()) continue
                val chunk = idx.map { variants[it] }
                val ports = freePorts(chunk.size)
                val core = XrayCore.Instance("test")
                val started = runCatching { core.start(XrayConfigBuilder.testConfig(chunk, ports, byeDpiPort, dpiPorts)) }
                if (started.isFailure || !core.running) {
                    // A broken link must not hide the others: fall back to one instance per variant.
                    core.stop()
                    chunk.forEachIndexed { i, v ->
                        val one = XrayCore.Instance("test1")
                        val p = freePorts(1)
                        val ok = runCatching { one.start(XrayConfigBuilder.testConfig(listOf(v), p, byeDpiPort, dpiPorts)) }.isSuccess && one.running
                        onEach(idx[i], if (ok) probeSocks(p[0], testUrl, big = big, youtube = youtube, attempts = attempts) else Probe(0, null, null, "конфиг не принят ядром"))
                        one.stop()
                    }
                    continue
                }
                try {
                    coroutineScope {
                        val sem = Semaphore(parallel)
                        chunk.indices.map { i ->
                            async { sem.withPermit { onEach(idx[i], if (skip(idx[i])) SKIPPED else probeSocks(ports[i], testUrl, big = big, youtube = youtube, attempts = attempts)) } }
                        }.awaitAll()
                    }
                } finally { core.stop() }
            }
        }
    }

    /**
     * Batches of variant indices. A WireGuard server appears at most once per batch: several tunnels with the same
     * key at once make the server roam between them and every variant but one stalls.
     */
    fun chunks(variants: List<Pair<Server, Mask?>>, batch: Int): List<List<Int>> {
        val out = mutableListOf<MutableList<Int>>()
        val wgIn = mutableListOf<MutableSet<String>>()
        variants.forEachIndexed { i, (s, _) ->
            val wg = s.protocol == "wireguard"
            // by private key: WARP endpoints of one account share the key and would steal the session from each other
            val key = s.secret
            var k = out.indices.firstOrNull { out[it].size < batch && (!wg || key !in wgIn[it]) } ?: -1
            if (k < 0) { out += mutableListOf<Int>(); wgIn += mutableSetOf<String>(); k = out.size - 1 }
            out[k] += i; if (wg) wgIn[k] += key
        }
        return out
    }

    /** SOCKS5 login for the stealth listener (java.net SOCKS client asks the default Authenticator). */
    fun installAuthenticator() = Authenticator.setDefault(object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val a = Tunnel.socks ?: return null
            if (!a.auth || requestingPort != a.port) return null
            return PasswordAuthentication(a.user, a.pass.toCharArray())
        }
    })

    data class DpiProbe(val ok: Boolean, val ms: Int, val bytes: Int, val error: String = "")
    /** YouTube page download speed through a SOCKS port (kbit/s, 0 = failed / cut): the whole page, up to 4 s. */
    fun ytSpeed(port: Int): Int = runCatching {
        val client = base.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).readTimeout(6, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
        val t0 = System.nanoTime()
        client.newCall(Request.Builder().url(DPI_URL).header("User-Agent", UA).build()).execute().use { r ->
            val src = r.body!!.byteStream(); val buf = ByteArray(32768); var total = 0L
            val end = t0 + 4_000_000_000L
            while (System.nanoTime() < end) { val n = src.read(buf); if (n < 0) break; total += n }
            client.connectionPool.evictAll()
            if (total < DPI_BYTES) 0 else (total * 8 * 1_000_000 / ((System.nanoTime() - t0) / 1000).coerceAtLeast(1) / 1000).toInt()
        }
    }.getOrDefault(0)

    const val DPI_URL = "https://www.youtube.com/"
    const val DPI_BYTES = 48_000

    /** DPI strategy check: the real YouTube page (≥48 KB) must download through the local DPI proxy — a tiny 204 passes even when throttled. */
    fun probeDpi(port: Int, attempts: Int = 2): DpiProbe {
        val client = base.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        var last = DpiProbe(false, 0, 0, "нет ответа")
        repeat(attempts) {
            val t0 = System.nanoTime()
            last = runCatching {
                client.newCall(Request.Builder().url(DPI_URL).header("User-Agent", UA).build()).execute().use { r ->
                    val src = r.body!!.byteStream(); val buf = ByteArray(16384); var total = 0
                    while (total < DPI_BYTES) { val n = src.read(buf); if (n < 0) break; total += n }
                    val ms = ((System.nanoTime() - t0) / 1_000_000).toInt().coerceAtLeast(1)
                    DpiProbe(total >= DPI_BYTES, ms, total, if (total >= DPI_BYTES) "" else "оборвалось на ${total / 1024} КБ")
                }
            }.getOrElse { DpiProbe(false, 0, 0, it.message ?: it.javaClass.simpleName) }
            if (last.ok) { client.connectionPool.evictAll(); return last }
        }
        client.connectionPool.evictAll()
        return last
    }

    /**
     * What a DPI strategy must really open (not only the YouTube page): the page, YouTube's image/video CDN
     * (throttled by SNI like googlevideo) and Discord. Score = how many passed (0-3); null bytes = skipped.
     */
    val DPI_SITES = listOf("https://www.youtube.com/" to 48_000, "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg" to 12_000, "https://discord.com/" to 16_000)
    data class DpiScore(val score: Int, val ms: Int, val youtube: Boolean, val error: String = "")
    fun probeDpiSites(port: Int): DpiScore {
        val client = base.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        var score = 0; var ms = 0; var yt = false; var err = ""
        DPI_SITES.forEachIndexed { i, (url, need) ->
            val t0 = System.nanoTime()
            val ok = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                    val src = r.body!!.byteStream(); val buf = ByteArray(16384); var total = 0
                    while (total < need) { val n = src.read(buf); if (n < 0) break; total += n }
                    total >= need || r.code in 200..399 && total > 0 && i > 0 && total >= r.body!!.contentLength().coerceAtLeast(1)
                }
            }.getOrElse { err = it.message ?: it.javaClass.simpleName; false }
            if (ok) { score++; ms += ((System.nanoTime() - t0) / 1_000_000).toInt(); if (i == 0) yt = true }
        }
        client.connectionPool.evictAll()
        return DpiScore(score, if (score > 0) ms / score else 0, yt, err)
    }

    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
}
