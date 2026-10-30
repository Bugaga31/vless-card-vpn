package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.XrayConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
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

data class Probe(val realMs: Int, val bigOk: Boolean?, val ytOk: Boolean?, val error: String = "", val tgOk: Boolean? = null, val kbps: Int = 0, val ytKbps: Int = 0, val bodyBytes: Long = 0, val bodyMs: Long = 0, val callsOk: Boolean? = null, val tgMs: Int = 0, val discordOk:Boolean?=null, val stableOk:Boolean?=null, val autoChecksKey:String="") {
    val works: Boolean get() = realMs > 0 && bigOk != false
}

/**
 * Honest connectivity tests, run from the phone outside the VPN:
 *  - TCP: connect time to server:port;
 *  - real: HTTPS request through Xray with the server's masking (like v2rayNG "real delay");
 *  - big: 256 KB download through the server — catches the TSPU "16 KB freeze" (connects, then hangs);
 *  - YouTube: bounded real video media, Telegram: unauthenticated MTProto reachability.
 * Many servers/masks are tested at once through one Xray instance (one SOCKS port per variant).
 */
object Tester {
    val SKIPPED = Probe(0, null, null, "пропущено")
    // All temporary core users share ProbeArbiter, including chain and throughput probes.
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
    const val BIG_BYTES = TransferProof.REQUIRED_BYTES

    /**
     * TCP ping of all servers at once: non-blocking sockets in one selector (no thread per server — the 96-thread pool
     * used to cap it), names resolved in parallel first (each distinct name once). Results arrive as they come.
     */
    suspend fun tcp(servers: List<Server>, parallel: Int = 96, timeoutMs: Int = 2500, onEach: (Server, Int) -> Unit) = coroutineScope {
        val names = servers.map { it.address }.distinct()
        val limit = PowerPolicy.cap(parallel, PhonePower.state.value.tcpParallel)
        val sem = Semaphore(minOf(32, limit))
        val ips = linkedMapOf<String, InetAddress?>()
        for (chunk in names.chunked(64)) {
            chunk.map { h -> async(Bg.io) { sem.withPermit { h to runCatching { InetAddress.getByName(RuntimeProbeAddress.host(h)) }.getOrNull() } } }
                .awaitAll().forEach { (host, address) -> ips[host] = address }
        }
        servers.filter { !it.isTcpBased }.forEach { onEach(it, if (ips[it.address] != null) 1 else 0) }
        val tcpList = servers.filter { it.isTcpBased }
        tcpList.filter { ips[it.address] == null }.forEach { onEach(it, 0) }
        val ok = tcpList.filter { ips[it.address] != null }
        kotlinx.coroutines.withContext(Bg.io) { nioPing(ok.map { InetSocketAddress(ips.getValue(it.address), it.port) }, timeoutMs, limit) { i, ms -> onEach(ok[i], ms) } }
    }

    /** Bounded TCP hint, never an authenticated Reality/TLS verdict. Timeout is unknown, not a blacklist. */
    suspend fun fastFilter(servers: List<Server>, budgetMs: Long = 600, foreground: Boolean = false): Map<String, Int> = coroutineScope {
        val plan = PhonePower.state.value
        val sem = Semaphore(PowerPolicy.cap(if (foreground) ConnectPolicy.FIRST else 6, if (foreground) plan.foregroundTcpParallel else plan.tcpParallel))
        // Shared only inside this call: no stale DNS cache crosses a network change.
        val names=servers.filter {it.isTcpBased}.map {RuntimeProbeAddress.host(it.address)}.distinct()
            .associateWith {host->async(start=kotlinx.coroutines.CoroutineStart.LAZY) {ProbeDns.resolve(host,budgetMs)}}
        servers.map { server -> async { sem.withPermit {
            val result = if (!server.isTcpBased) -1 else {
                val began=System.nanoTime()
                kotlinx.coroutines.withTimeoutOrNull(budgetMs.coerceIn(1L,60000L)) {
                    val addresses=names.getValue(RuntimeProbeAddress.host(server.address)).await()
                        ?: return@withTimeoutOrNull -1
                    val remaining=budgetMs-(System.nanoTime()-began)/1000000L
                    StaggeredConnect.hint(DualStackOrder.order(addresses),remaining) {address,left->
                        try {CancellableProbe.run {bind->
                            val socket=Socket();bind {socket.close()}
                            socket.use {it.connect(InetSocketAddress(address,server.port),left.coerceIn(1L,60000L).toInt())}
                            true
                        }} catch(e:kotlinx.coroutines.CancellationException) {throw e}
                        catch(_:java.net.SocketTimeoutException) {null}
                        catch(_:Exception) {false}
                    }
                } ?: -1
            }
            server.id to result
        } } }.awaitAll().toMap()
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
    fun probeSocks(port: Int, testUrl: String, big: Boolean = true, youtube: Boolean = true, telegram: Boolean = youtube, attempts: Int = 2, budgetMs: Long = 0, onCall: ((okhttp3.Call) -> Unit)? = null, calls: Boolean = false, user: String = "", pass: String = "", onCancel: ((() -> Unit) -> Unit)? = null, autoChecks:AutoCheckProfile?=null): Probe {
        val budgetDeadline = if (budgetMs > 0) System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceAtMost(60000)) else Long.MAX_VALUE
        fun boundedCall(client: OkHttpClient, request: Request): okhttp3.Call = client.newCall(request).also { call ->
            if (budgetDeadline != Long.MAX_VALUE) call.timeout().timeout(TimeUnit.NANOSECONDS.toMillis((budgetDeadline-System.nanoTime()).coerceAtLeast(1)).coerceAtLeast(1),TimeUnit.MILLISECONDS)
            onCall?.invoke(call)
        }
        val client = (if (attempts == 1) fast else base).newBuilder().apply {
            if (budgetMs > 0) { callTimeout(budgetMs, TimeUnit.MILLISECONDS); connectTimeout(minOf(budgetMs, 2500), TimeUnit.MILLISECONDS); readTimeout(budgetMs, TimeUnit.MILLISECONDS) }
        }.dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool(2, 30, TimeUnit.SECONDS))
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).build()
        val mediaClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val tasks = mutableListOf<java.util.concurrent.Future<*>>()
        try {
            // Independent service evidence starts with HTTPS, not after it. The same route/SOCKS only.
            val tgF = if (autoChecks?.telegram ?: telegram) telegramPool.submit<TelegramProbe.Result> {
                val left=if(budgetDeadline==Long.MAX_VALUE) 3000L else
                    TimeUnit.NANOSECONDS.toMillis((budgetDeadline-System.nanoTime()).coerceAtLeast(1)).coerceAtLeast(1)
                TelegramProbe.measure(port,minOf(maxOf(3000L,AutoLatencyPolicy.MAX_MS.toLong()+100L),left),onCancel)
            }.also {tasks+=it} else null
            var best = Long.MAX_VALUE; var err = ""
            repeat(attempts.coerceIn(1, 3)) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Проверка отменена")
                val t0 = System.nanoTime()
                runCatching {
                    boundedCall(client,Request.Builder().url(testUrl).header("User-Agent", UA).build()).execute().use { r ->
                        check(ProbeResponsePolicy.connectivity(r.code, testUrl)) { "Unexpected connectivity HTTP ${r.code}" }
                        r.body?.byteStream()?.use { it.read(ByteArray(4096)) }
                    }
                    best = minOf(best, (System.nanoTime() - t0) / 1_000_000)
                }.onFailure { err = Errors.human(it) }
            }
            if (best == Long.MAX_VALUE) return Probe(0, null, null, err)
            val deadline = if (budgetDeadline == Long.MAX_VALUE) System.nanoTime() + TimeUnit.SECONDS.toNanos(40) else budgetDeadline
            fun remaining() = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(1)).coerceAtLeast(1)
            val bigF = if (big) pool.submit<BodyResult> { bigDownload(client,budgetDeadline,onCall) }.also { tasks += it } else null
            val ytF = if (autoChecks?.youtube ?: youtube) pool.submit<YouTubeMediaProbe.Result> {
                YouTubeMediaProbe.check { boundedCall(mediaClient, it) }
            }.also { tasks += it } else null
            val callF = if (autoChecks?.voice ?: calls) pool.submit<Boolean?> {
                if(autoChecks?.voice==true) {
                    val t=UdpCallProbe.train(port,user,pass,minOf(4000L,remaining()).toInt(),CallTransportPolicy.PACKETS,onCancel)
                    CallTransportPolicy.assess(t.sent,t.rttMs,t.complete,AutoLatencyPolicy.MAX_MS).grade.let {grade->
                        if(grade==CallTransportPolicy.Grade.UNKNOWN) null else grade==CallTransportPolicy.Grade.PASS
                    }
                } else UdpCallProbe.probe(port, user, pass, minOf(1800L, remaining()).toInt(), onCancel)
            }.also { tasks += it } else null
            val discordF = if(autoChecks?.discord==true) pool.submit<Boolean?> {
                runCatching { boundedCall(client,Request.Builder().url("https://discord.com/api/v10/gateway").build()).execute().use {r->
                    r.code==200 && r.body?.byteStream()?.use {input-> val bytes=ByteArray(8193); var n=0; while(n<bytes.size) {val x=input.read(bytes,n,bytes.size-n);if(x<0) break;n+=x}; if(n>8192) null else String(bytes,0,n,Charsets.UTF_8)}?.let {body->
                        val url=org.json.JSONObject(body).optString("url")
                        url=="wss://gateway.discord.gg" || url=="wss://gateway.discord.gg/"
                    }==true
                }}.getOrDefault(false)
            }.also {tasks+=it} else null
            val tgResult = ProbeTasks.await(tgF, remaining(), TelegramProbe.Result(null,0))
            val stabilityF = if(autoChecks?.stability==true) telegramPool.submit<Boolean?> {
                val samples=mutableListOf(LinkStabilityPolicy.Sample(tgResult.ok,tgResult.ms))
                repeat(2) {
                    if(remaining()<50) return@submit null
                    val x=TelegramProbe.measure(port,minOf(2000L,remaining()),onCancel)
                    samples+=LinkStabilityPolicy.Sample(x.ok,x.ms)
                }
                LinkStabilityPolicy.summarize(samples,AutoLatencyPolicy.MAX_MS).stable
            }.also {tasks+=it} else null
            val bigResult = ProbeTasks.await(bigF, remaining(), BodyResult())
            val ytResult = ProbeTasks.await(ytF, remaining(), YouTubeMediaProbe.Result(null))
            val callsOk = if (callF != null) ProbeTasks.await(callF, remaining(), null) else null
            val discordOk=ProbeTasks.await(discordF,remaining(),null)
            val stableOk=ProbeTasks.await(stabilityF,remaining(),null)
            val result=Probe(best.toInt().coerceAtLeast(1), if (bigF != null) bigResult.ok else null, ytResult.ok, tgOk = tgResult.ok, tgMs = if(tgResult.ok==true) tgResult.ms else 0, kbps = bigResult.kbps, ytKbps = ytResult.kbps, bodyBytes = bigResult.bytes, bodyMs = bigResult.ms, callsOk = callsOk, discordOk=discordOk,stableOk=stableOk)
            return if(autoChecks!=null) result.copy(autoChecksKey=AutoChecks.stamp(result,autoChecks,AutoLatencyPolicy.MAX_MS)) else result
        } finally {
            ProbeTasks.cancel(tasks)
            // Private dispatcher/pool: cancellation cannot cancel another probe or the live VPN's HTTP calls.
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
        }
    }

    // Media downloads cannot occupy all workers needed to establish native Telegram evidence.
    private val telegramPool = java.util.concurrent.Executors.newFixedThreadPool(8) { r -> Thread(r, "probe-tg").apply { isDaemon = true } }
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(12) { r -> Thread(r, "probe").apply { isDaemon = true } }

    /** 256 KB through the server: (passed, kbit/s). A freeze (read timeout — the TSPU "16 KB" cut) fails at once, no second mirror. */
    private data class BodyResult(val ok: Boolean = false, val kbps: Int = 0, val bytes: Long = 0, val ms: Long = 0)

    private fun bigDownload(client: OkHttpClient, deadline: Long = Long.MAX_VALUE, onCall: ((okhttp3.Call) -> Unit)? = null): BodyResult {
        for (url in BIG_URLS) {
            val t0 = System.nanoTime()
            try {
                if (System.nanoTime() >= deadline) return BodyResult()
                val sample = download(client, url, deadline, onCall)
                if (sample.bytes >= BIG_BYTES) return BodyResult(true, minOf(sample.slowestKbps,TransferProof.rate(sample.bytes,System.nanoTime()-t0)), sample.bytes.toLong(), ((System.nanoTime()-t0)/1_000_000).coerceAtLeast(1))
            } catch (e: java.io.InterruptedIOException) { return BodyResult() } catch (e: Exception) { /* mirror down: next */ }
        }
        return BodyResult()
    }

    /** Cloudflare trace through the running tunnel: "on" / "plus" when traffic really goes through WARP, "off" otherwise, null = no answer. */
    fun warpTrace(port: Int): String? = runCatching {
        val client = fast.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).build()
        client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").header("User-Agent", UA).build()).execute().use { r ->
            r.body?.string().orEmpty().lineSequence().firstOrNull { it.startsWith("warp=") }?.substringAfter('=')?.trim()
        }
    }.getOrNull()

    /** DNS query (A record) in wire format, id = [id]. */
    fun dnsQuery(host: String, id: Int = 0x5a5a): ByteArray = DnsWire.query(host, id)

    /**
     * The phone's DNS path as apps use it: SOCKS5 UDP ASSOCIATE → 1.1.1.1:53 → Xray's DNS. HTTP checks go via socks5h
     * (Xray resolves on the server side), so a dead DNS path used to look healthy while apps could not open anything.
     */
    fun dnsViaSocks(port: Int, user: String = "", pass: String = "", host: String = "www.google.com", budgetMs: Int = 5000, onCancel: ((() -> Unit) -> Unit)? = null): Boolean = runCatching {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceIn(1, 5000).toLong())
        fun remaining(): Int {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) throw java.net.SocketTimeoutException("DNS deadline")
            return left.toInt().coerceAtLeast(1)
        }
        java.net.Socket().use { s ->
            onCancel?.invoke { s.close() }
            s.connect(InetSocketAddress("127.0.0.1", port), minOf(2000, remaining())); s.soTimeout = remaining()
            val o = s.getOutputStream(); val i = java.io.DataInputStream(s.getInputStream())
            o.write(if (user.isNotEmpty()) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0)); o.flush()
            val m = ByteArray(2); i.readFully(m); check(m[0].toInt() == 5 && m[1].toInt() == if (user.isNotEmpty()) 2 else 0)
            if (m[1].toInt() == 2) {
                val u = user.toByteArray(); val p = pass.toByteArray(); check(u.size in 1..255 && p.size <= 255)
                o.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); o.flush()
                val r = ByteArray(2); i.readFully(r); check(r[0].toInt() == 1 && r[1].toInt() == 0)
            } else check(m[1].toInt() == 0)
            o.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
            s.soTimeout = remaining()
            val h = ByteArray(4); i.readFully(h); check(h[0].toInt() == 5 && h[1].toInt() == 0 && h[2].toInt() == 0)
            val addr = when (h[3].toInt()) { 1 -> ByteArray(4); 4 -> ByteArray(16); else -> error("atyp") }.also { i.readFully(it) }
            val rport = i.readUnsignedShort(); check(rport > 0)
            val relay = InetAddress.getByAddress(addr).let { if (it.isAnyLocalAddress) InetAddress.getByName("127.0.0.1") else it }
            java.net.DatagramSocket().use { u ->
                onCancel?.invoke { u.close(); s.close() }
                u.connect(relay, rport)
                u.soTimeout = remaining()
                val q = dnsQuery(host, (System.nanoTime() and 0xffff).toInt())
                val pkt = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53) + q
                u.send(java.net.DatagramPacket(pkt, pkt.size, relay, rport))
                val buf = ByteArray(1500); val dp = java.net.DatagramPacket(buf, buf.size); u.receive(dp)
                val response = DnsWire.payload(buf, dp.length)
                response != null && DnsWire.hasAnswer(response, q)
            }
        }
    }.getOrDefault(false)

    /** hev stats [txPackets, txBytes, rxPackets, rxBytes] between two samples: true when one direction moves and the other is dead. */
    fun tunStalled(a: LongArray?, b: LongArray?): Boolean {
        if (a == null || b == null || a.size < 4 || b.size < 4) return false
        val dTx = b[0] - a[0]; val dRx = b[2] - a[2]
        return (dTx >= 200 && dRx == 0L) || (dRx >= 200 && dTx == 0L)
    }

    /** Country (ISO) the internet sees through the tunnel — Cloudflare trace «loc=». null = no answer. */
    fun exitCountry(port: Int, budgetMs: Long = 0, onCall: ((okhttp3.Call) -> Unit)? = null): String? = runCatching {
        val client = fast.newBuilder().dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool())
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .apply {if(budgetMs>0)callTimeout(budgetMs,TimeUnit.MILLISECONDS)}.build()
        try {
            client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").header("User-Agent", UA).build())
                .also {onCall?.invoke(it)}.execute().use { r -> traceLoc(r.body?.string().orEmpty()) }
        } finally { client.dispatcher.cancelAll();client.connectionPool.evictAll() }
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
                var received=0
                r.body?.byteStream()?.let { src -> val buf = ByteArray(16384); while (received < 40_000) { val n = src.read(buf); if (n < 0) break; received += n } }
                SiteProbe(ProbeResponsePolicy.site(r.code,received), ((System.nanoTime() - t0) / 1_000_000).toInt(), r.code)
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
    suspend fun speedMany(variants: List<Pair<Server, Mask?>>, byeDpiPort: Int?, dpiPorts: Map<String, Int>, seconds: Int = 3): List<Int> =
        variants.map { variant ->
            ProbeArbiter.withPermit {
                withContext(Bg.io) {
                    val ports=freePorts(1)
                    val core=XrayCore.Instance("speed")
                    try {
                        runCatching {core.start(XrayConfigBuilder.testConfig(listOf(variant),ports,byeDpiPort,dpiPorts))}
                        if(!core.running) 0 else runCatching {(speed(ports[0],seconds).mbps*1000).toInt()}.getOrDefault(0)
                    } finally {core.stop()}
                }
            }
        }

    private fun download(client: OkHttpClient, url: String, deadline: Long = Long.MAX_VALUE, onCall: ((okhttp3.Call) -> Unit)? = null): TransferProof.Sample =
        client.newCall(Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache, no-store").build()).also { call ->
            if (deadline != Long.MAX_VALUE) call.timeout().timeout(TimeUnit.NANOSECONDS.toMillis((deadline-System.nanoTime()).coerceAtLeast(1)).coerceAtLeast(1),TimeUnit.MILLISECONDS)
            onCall?.invoke(call)
        }.execute().use { r ->
            check(ProbeResponsePolicy.download(r.code, r.header("Content-Type"), r.header("Content-Encoding"))) { "Invalid speed-test response" }
            check(TransferProof.rangeValid(r.code,r.header("Content-Range"),BIG_BYTES)) { "Invalid download byte range" }
            TransferProof.read(r.body!!.byteStream(),deadline)
        }

    /** Serialize chain downloads with ordinary server/mask tests. Waiting is cancellable. */
    suspend fun <T> exclusiveProbe(block: suspend () -> T): T = ProbeArbiter.withPermit { block() }

    /** Tests [variants] (server + mask) in parallel through one temporary Xray instance. */
    suspend fun real(
        variants: List<Pair<Server, Mask?>>, testUrl: String, byeDpiPort: Int?, dpiPorts: Map<String, Int> = emptyMap(), youtube: Boolean = true,
        batch: Int = 32, parallel: Int = 12, attempts: Int = 2, big: Boolean = true, telegram: Boolean = youtube, calls: Boolean = false, foreground: Boolean = false,
        /** Early stop: variants for which this returns true are not probed (reported as skipped). */
        requireTelegram: Boolean = false, autoChecks:AutoCheckProfile?=null, skip: (Int) -> Boolean = { false },
        stopAfter: Int = Int.MAX_VALUE,
        budgetMs: Long = 0,
        onStarted: ((Int) -> Unit)? = null,
        onEach: (Int, Probe) -> Unit,
    ) = withContext(Bg.io) {
        run {
            val serviceBudget = if(autoChecks!=null) maxOf(budgetMs,6500L) else StrictProbeBudget.budget(budgetMs, foreground, requireTelegram, big, youtube, calls, attempts)
            val successes = java.util.concurrent.atomic.AtomicInteger()
            fun skipped(i: Int) = successes.get() >= stopAfter.coerceAtLeast(1) || skip(i) || (variants[i].first.protocol == "wireguard" && variants[i].first.secret in Tunnel.wireguardKeys)
            fun accepted(p: Probe) = if(autoChecks!=null) autoChecks.accepts(p,AutoLatencyPolicy.MAX_MS) else p.works && (!requireTelegram || AutoLatencyPolicy.accepts(p))
            fun report(i: Int, p: Probe) { if (accepted(p)) successes.incrementAndGet(); onEach(i, p) }
            // Bound config/socket footprint too, not merely simultaneous HTTP calls.
            val plan = PhonePower.state.value
            val probeLimit = if (foreground) plan.foregroundRealParallel else plan.realParallel
            val effectiveBatch = PowerPolicy.cap(batch, if(foreground) ConnectPolicy.FIRST else minOf(6,probeLimit.coerceAtLeast(1)))
            for (all in chunks(variants, effectiveBatch)) {
                val idx = all.filter { i -> if (skipped(i)) { onEach(i, SKIPPED); false } else true }
                if (idx.isEmpty()) continue
                ProbeArbiter.withPermit(foreground) {
                // Re-evaluate skip after waiting; another successful batch may have satisfied stopAfter.
                if(idx.all { skipped(it) }) {idx.forEach {onEach(it,SKIPPED)};return@withPermit}
                val chunk = idx.map { variants[it] }
                val ports = freePorts(chunk.size)
                val core = XrayCore.Instance("test")
                val started = runCatching { core.start(XrayConfigBuilder.testConfig(chunk, ports, byeDpiPort, dpiPorts)) }
                if (started.isFailure || !core.running) {
                    // A broken link must not hide the others: fall back to one instance per variant.
                    core.stop()
                    chunk.forEachIndexed { i, v ->
                        if (skipped(idx[i])) { onEach(idx[i], SKIPPED); return@forEachIndexed }
                        onStarted?.invoke(idx[i])
                        val one = XrayCore.Instance("test1")
                        val p = freePorts(1)
                        val ok = runCatching { one.start(XrayConfigBuilder.testConfig(listOf(v), p, byeDpiPort, dpiPorts)) }.isSuccess && one.running
                        try { report(idx[i], if (ok) CancellableProbe.run { bind -> probeSocks(p[0], testUrl, big = big, youtube = youtube, telegram = telegram, attempts = attempts, budgetMs = serviceBudget, onCall = { call -> bind { call.cancel() } }, calls = calls, onCancel = bind, autoChecks=autoChecks) } else Probe(0, null, null, "конфиг не принят ядром")) }
                        finally { one.stop() }
                    }
                    return@withPermit
                }
                try {
                    coroutineScope {
                        ProbeRace.run(chunk.indices.toList(), PowerPolicy.cap(parallel, probeLimit), stopAfter,
                            skip = { i -> skipped(idx[i]) }, staggerMs = ForegroundProbePlan.staggerMs(foreground,stopAfter),
                            probe = { i -> onStarted?.invoke(idx[i]); CancellableProbe.run { bind -> probeSocks(ports[i], testUrl, big = big, youtube = youtube, telegram = telegram,
                                attempts = attempts, budgetMs = serviceBudget, onCall = { call -> bind { call.cancel() } }, calls = calls, onCancel = bind, autoChecks=autoChecks) } },
                            accepted = { accepted(it) }, onEach = { i, p -> report(idx[i], p) })
                    }
                } finally { core.stop() }
                }
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
    /** Actual media only. HTML/image throughput must never choose YouTube's route. */
    fun ytSpeed(port: Int): Int = youtubeMedia(port).kbps

    private fun mediaClient(port: Int) = base.newBuilder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        .followRedirects(false).followSslRedirects(false).readTimeout(3, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool(1, 10, TimeUnit.SECONDS)).build()

    private fun mediaCall(client: OkHttpClient, request: Request, deadline: Long): okhttp3.Call = client.newCall(request).also {
        it.timeout().timeout(TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(1)).coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }

    fun youtubeMedia(port: Int): YouTubeMediaProbe.Result {
        val client = mediaClient(port)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        return try { YouTubeMediaProbe.check { mediaCall(client, it, deadline) } }
        finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    }

    /** Compare the same signed video and sample size, never two HTML pages. No stored media URLs. */
    fun compareYoutubeMedia(serverPort: Int, dpiPort: Int): Pair<Int, Int> {
        val server = mediaClient(serverPort); val dpi = mediaClient(dpiPort)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        return try {
            val url = YouTubeMediaProbe.discover { mediaCall(server, it, deadline) }
                ?: YouTubeMediaProbe.discover { mediaCall(dpi, it, deadline) } ?: return 0 to 0
            val a = YouTubeMediaProbe.measure(url) { mediaCall(server, it, deadline) }
            val b = YouTubeMediaProbe.measure(url) { mediaCall(dpi, it, deadline) }
            a.kbps to b.kbps
        } finally {
            listOf(server, dpi).forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll() }
        }
    }

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
            }.getOrElse { DpiProbe(false, 0, 0, Errors.human(it)) }
            if (last.ok) { client.connectionPool.evictAll(); return last }
        }
        client.connectionPool.evictAll()
        return last
    }

    /**
     * What a DPI strategy must really open (not only the YouTube page): the page, YouTube's image CDN (not video playback) and Discord. Score = how many passed (0-3); null bytes = skipped.
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
            }.getOrElse { err = Errors.human(it); false }
            if (ok) { score++; ms += ((System.nanoTime() - t0) / 1_000_000).toInt(); if (i == 0) yt = true }
        }
        client.connectionPool.evictAll()
        return DpiScore(score, if (score > 0) ms / score else 0, yt, err)
    }

    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
}
