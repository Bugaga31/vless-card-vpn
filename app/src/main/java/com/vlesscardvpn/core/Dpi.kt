package com.vlesscardvpn.core

import android.content.Context
import android.os.Build
import com.vlesscardvpn.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Local anti-DPI engines bundled as non-root child processes (SOCKS5 on 127.0.0.1). */
enum class DpiEngine(val library: String, val title: String) {
    BYEDPI("libbyedpi.so", "ByeDPI"),
    TPWS("libtpws.so", "zapret"),
    /** In-process Xray instance: SOCKS → freedom with finalmask fragment (VLESS Card "ladder" patterns). */
    XRAY("", "Xray")
}

/** One desync strategy: ByeDPI or zapret tpws arguments (long form, no listen options). */
data class DpiStrategy(val id: String, val label: String, val engine: DpiEngine, val args: List<String>, val own: Boolean = false) {
    /** ByeDPI `--auto` groups learn per site: the first connection may be spent detecting the DPI. */
    val adaptive: Boolean get() = args.any { it.startsWith("--auto") }
    fun argv(port: Int, mask: String): List<String> {
        val domain = ByeDpiArgs.maskDomain(mask)
        val body = args.flatMap { a ->
            if (a == DpiStrategies.MASK_POOL) DpiStrategies.maskPool(domain).flatMap { listOf("--fake-sni", it) }
            else listOf(a.replace(ByeDpiArgs.SNI_PLACEHOLDER, domain))
        }
        return when (engine) {
            DpiEngine.BYEDPI -> ByeDpiArgs.loopbackPrefix(port) + body
            DpiEngine.TPWS -> DpiStrategies.tpwsPrefix(port) + body
            DpiEngine.XRAY -> listOf("port=$port") + body
        }
    }
}

/**
 * Built-in strategies (ported from VLESS Card 1.0.57/1.0.58, where each passed HTTPS on host and emulator checks):
 * popular ByeByeDPI lines, zapret tpws (socks mode, no root) and five own "VLESS Card" ideas.
 */
object DpiStrategies {
    const val CUSTOM_ID = "custom"
    /** Expands to several `--fake-sni` (ByeDPI picks one at random per connection): user's mask first. */
    const val MASK_POOL = "{mask_pool}"
    private val POOL = listOf("ya.ru", "vk.com", "gosuslugi.ru", "ozon.ru", "mail.ru")
    fun maskPool(userMask: String): List<String> = (listOf(ByeDpiArgs.maskDomain(userMask)) + POOL).distinct()
    private const val S = ByeDpiArgs.SNI_PLACEHOLDER

    private fun bye(id: String, label: String, vararg a: String) = DpiStrategy("BYEDPI#$id", "ByeDPI · $label", DpiEngine.BYEDPI, a.toList())
    private fun tpws(id: String, label: String, vararg a: String) = DpiStrategy("TPWS#$id", "zapret · $label", DpiEngine.TPWS, a.toList())
    private fun own(id: String, label: String, engine: DpiEngine, vararg a: String) =
        DpiStrategy("${engine.name}#$id", "VLESS Card · $label", engine, a.toList(), own = true)
    /** Xray fragment strategy: key=value args (packets, length/delay or lengths/delays lists, maxSplit). */
    private fun xr(id: String, label: String, vararg a: String) = own(id, "Xray $label", DpiEngine.XRAY, *a)

    val XRAY_LADDER = xr("LADDER", "лесенка Hello 1→3→8→30", "packets=tlshello", "lengths=1-1,2-4,5-10,20-60", "delays=1-2,2-4,3-6,5-10")

    /** Every site starts with the gentlest desync; after a DPI reset/timeout/TLS error ByeDPI escalates (cached per IP). */
    val CASCADE = own("VCARD_CASCADE", "адаптивный каскад", DpiEngine.BYEDPI,
        "--disorder", "1", "--tlsrec", "1+s",
        "--auto=torst,ssl_err", "--oob", "1", "--disorder", "1+s", "--tlsrec", "1+s",
        "--auto=torst,ssl_err", "--split", "1+s", "--disorder", "3+s", "--fake", "-1", "--ttl", "5", MASK_POOL,
        "--fake-tls-mod", "rand,orig", "--timeout", "3")
    /** SNI shredded into 3-byte TCP pieces inside its own TLS record, first byte sent last. */
    val SHRED = own("VCARD_SHRED", "шредер SNI", DpiEngine.BYEDPI, "--disorder", "1", "--split", "2:4:3+s", "--tlsrec", "1+s")
    /** Split at the SNI + TLS record split; only after a reset/timeout a low-TTL fake with the mask domain. */
    val STEALTH = own("VCARD_STEALTH", "сплит SNI → маскировка", DpiEngine.BYEDPI,
        "--split", "1+s", "--disorder", "3+s", "--tlsrec", "1+s", "--auto=torst",
        "--fake", "-1", "--ttl", "6", "--fake-sni", S, "--fake-tls-mod", "rand,orig")
    /** Record-layer version 0x0304 (RFC 8446: receivers ignore it) breaks naive "16 03 01" signatures. */
    val RECVER = own("VCARD_RECVER", "другая версия TLS-записи", DpiEngine.BYEDPI, "--tlsminor", "4", "--tlsrec", "1+s", "--disorder", "1")
    /** zapret: TLS record cut inside the SNI + cuts at every host boundary, reversed. */
    val HOST_SHRED = own("VCARD_SHRED", "шредер хоста (zapret)", DpiEngine.TPWS, "--tlsrec=sniext+1", "--split-pos=1,host,midsld,endhost-1", "--disorder")

    /** Order: what ByeByeDPI users report working most often first; TTL fakes (break near servers) last. */
    val BUILT_IN: List<DpiStrategy> = listOf(
        bye("OOB_THEN_DISORDER", "авто: OOB → порядок", "--oob", "1", "--auto=torst", "--disorder", "1"),
        CASCADE,
        bye("DISORDER", "обратный порядок", "--disorder", "1"),
        tpws("SPLIT_DISORDER", "сплит + disorder", "--split-pos=1,midsld", "--disorder"),
        SHRED,
        bye("MULTI_DISORDER", "многократный порядок", "--disorder", "1", "--split", "1+s", "--disorder", "3+s", "--split", "6+s", "--disorder", "9+s", "--split", "12+s"),
        STEALTH,
        HOST_SHRED,
        tpws("TLSREC", "TLS-записи по SNI", "--tlsrec=sniext", "--split-pos=1,midsld"),
        RECVER,
        bye("SPLIT_DISORDER", "разбиение + обратный порядок", "--split", "1+s", "--disorder", "3+s"),
        bye("MASK_AUTO_FAKE", "авто: порядок → маскировка", "--disorder", "1", "--auto=torst", "--fake", "-1", "--ttl", "8", "--fake-sni", S, "--fake-tls-mod", "rand,orig"),
        tpws("OOB", "OOB-байт", "--oob", "--split-pos=1"),
        bye("DISOOB", "OOB + обратный порядок", "--disoob", "1"),
        tpws("HOST_DISORDER", "границы хоста + disorder", "--split-pos=1,host,midsld,endhost", "--disorder"),
        bye("COMBINED", "TCP + TLS", "--split", "1+s", "--tlsrec", "1+s"),
        tpws("TLSREC_OOB", "TLS-запись + OOB", "--tlsrec=sniext+1", "--split-pos=1,sniext+1", "--oob=tls"),
        bye("SNI_EDGES", "границы SNI", "--split", "1+s", "--split", "-1+se", "--tlsrec", "1+s", "--tlsrec", "-1+se"),
        bye("MASK_FAKE", "маскировка фейк-SNI", "--disorder", "1", "--fake", "-1", "--ttl", "8", "--fake-sni", S, "--fake-tls-mod", "orig"),
        bye("MASK_FAKE_RAND", "маскировка + случайный TLS", "--fake", "-1", "--ttl", "8", "--fake-sni", S, "--fake-tls-mod", "rand,orig"),
        tpws("MSS", "малый MSS", "--mss=88", "--split-pos=1"),
        // VLESS Card × Xray-core: TCP fragmentation of the ClientHello inside the app (no extra binary, works on any ABI).
        XRAY_LADDER,
        xr("DUST", "пыль Hello 1-5 байт", "packets=tlshello", "length=1-5", "delay=0-1"),
        xr("SLOW", "медленный Hello", "packets=tlshello", "length=10-30", "delay=10-20"),
        xr("BIG", "крупные куски Hello", "packets=tlshello", "length=100-200", "delay=10-20"),
        xr("FIRST", "1 байт + пауза", "packets=1-1", "lengths=1-1,500-1000", "delays=60-120,1-2"),
    )

    fun custom(line: String, mask: String): DpiStrategy? = ByeDpiArgs.parse(line, mask).getOrNull()
        ?.let { DpiStrategy(CUSTOM_ID, "Своя строка: ${line.take(40)}", DpiEngine.BYEDPI, it) }

    fun byId(id: String?, s: Settings): DpiStrategy? = when {
        id == null || id == "" || id == Settings.DPI_AUTO -> null
        id == CUSTOM_ID -> custom(s.byeDpiArgs, s.byeDpiSni)
        id.startsWith(DpiEvo.PREFIX) -> DpiEvo.decode(id)
        else -> BUILT_IN.firstOrNull { it.id == id }
    }

    /** Strategy for this network: fixed choice, or (auto) the one found for this network, then any found, then the custom line. */
    fun resolve(s: Settings, network: String): DpiStrategy {
        byId(s.dpiStrategy, s)?.let { return it }
        return byId(s.dpiRemembered[network], s) ?: byId(s.dpiRemembered[Settings.ANY_NETWORK], s)
            ?: custom(s.byeDpiArgs, s.byeDpiSni) ?: BUILT_IN.first()
    }

    /** Search order: custom line, the remembered ones of this network (best first), then all built-ins (zapret only if the binary exists). */
    fun plan(s: Settings, network: String, tpwsAvailable: Boolean): List<DpiStrategy> = buildList {
        custom(s.byeDpiArgs, s.byeDpiSni)?.let { add(it) }
        byId(s.dpiRemembered[network], s)?.let { add(it) }
        s.dpiRanking[network].orEmpty().forEach { id -> byId(id, s)?.let { add(it) } }
        addAll(BUILT_IN)
    }.filter { tpwsAvailable || it.engine != DpiEngine.TPWS }.distinctBy { it.id }

    /** Xray config of an [DpiEngine.XRAY] strategy: SOCKS 127.0.0.1:port → freedom with the fragment finalmask. */
    fun xrayConfig(s: DpiStrategy, port: Int): String {
        val kv = s.args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val f = org.json.JSONObject().put("packets", kv["packets"] ?: "tlshello")
        fun list(v: String) = org.json.JSONArray(v.split(',').map { it.trim() })
        if (kv["lengths"] != null) f.put("lengths", list(kv.getValue("lengths"))).put("delays", list(kv["delays"] ?: "1-2"))
        else f.put("length", kv["length"] ?: "1-3").put("delay", kv["delay"] ?: "1-3")
        kv["maxSplit"]?.let { f.put("maxSplit", it) }
        val o = org.json.JSONObject()
        o.put("log", org.json.JSONObject().put("loglevel", "warning"))
        o.put("inbounds", org.json.JSONArray().put(org.json.JSONObject().put("tag", "in").put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
            .put("settings", org.json.JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1"))))
        o.put("outbounds", org.json.JSONArray().put(org.json.JSONObject().put("tag", "out").put("protocol", "freedom")
            .put("streamSettings", org.json.JSONObject().put("sockopt", org.json.JSONObject().put("domainStrategy", "UseIPv4"))
                .put("finalmask", org.json.JSONObject().put("tcp", org.json.JSONArray()
                .put(org.json.JSONObject().put("type", "fragment").put("settings", f)))))))
        return o.toString()
    }

    fun tpwsPrefix(port: Int): List<String> {
        require(port in 1024..65535)
        return listOf("--socks", "--bind-addr=127.0.0.1", "--port=$port", "--maxconn=256")
    }
}

/** Runs one strategy as a local SOCKS5 proxy. Its sockets bypass the VPN (the app UID is excluded). */
class DpiProxy(private val context: Context) : AutoCloseable {
    private var process: Process? = null
    private var xray: XrayCore.Instance? = null
    var port: Int = 0; private set
    var strategy: DpiStrategy? = null; private set

    fun available(engine: DpiEngine): Boolean = if (engine == DpiEngine.XRAY) true else Build.VERSION.SDK_INT >= 26 && exe(engine).let { it.isFile && it.canExecute() }
    private fun exe(engine: DpiEngine) = File(context.applicationInfo.nativeLibraryDir, engine.library)

    /** Starts [s]; returns the loopback port. [allowLocal] lets zapret reach loopback/LAN targets (debug e2e only). */
    suspend fun start(s: DpiStrategy, mask: String, allowLocal: Boolean = false): Int = withContext(Dispatchers.IO) {
        close()
        check(available(s.engine)) { "${s.engine.title} недоступен на этом устройстве (нужен Android 8+)" }
        val p = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        if (s.engine == DpiEngine.XRAY) {
            XrayCore.init(context)
            val x = XrayCore.Instance("dpi-${s.id}"); xray = x
            try { x.start(DpiStrategies.xrayConfig(s, p)); check(x.running) { "Xray не запустился: ${x.lastStatus}" } } catch (e: Throwable) { close(); throw e }
            port = p; strategy = s
            return@withContext p
        }
        val b = ProcessBuilder(listOf(exe(s.engine).absolutePath) + s.argv(p, mask))
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
        if (allowLocal) b.environment()["VCVPN_TPWS_ALLOW_LOCAL"] = "1"
        val child = b.start()
        process = child
        try {
            withTimeout(3000) {
                while (true) {
                    ensureActive()
                    check(child.isAlive) { "${s.engine.title} завершился при запуске — проверьте стратегию" }
                    if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 150) }; true }.getOrDefault(false)) break
                    delay(50)
                }
            }
            port = p; strategy = s; p
        } catch (e: Throwable) { close(); throw e }
    }

    val alive: Boolean get() = process?.isAlive == true || xray?.running == true

    override fun close() {
        xray?.let { it.stop(); xray = null; port = 0 }
        val old = process.also { process = null } ?: return
        port = 0
        old.destroy()
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            if (!old.waitFor(300, TimeUnit.MILLISECONDS)) { old.destroyForcibly(); old.waitFor(300, TimeUnit.MILLISECONDS) }
        }
    }
}

/**
 * The current strategy (for ByeDPI/Hybrid modes and ".b" masks) plus one engine per fixed strategy used by
 * server masks ("Chrome + VLESS Card · каскад" etc.). Engines that fail to start are skipped: their masks
 * fall back to the current strategy (see XrayConfigBuilder.resolveMask).
 */
class DpiSet(private val context: Context) : AutoCloseable {
    private val procs = mutableListOf<DpiProxy>()
    var current: DpiStrategy? = null; private set
    var currentPort: Int? = null; private set
    val ports = LinkedHashMap<String, Int>()

    suspend fun start(s: Settings, network: String, needCurrent: Boolean, fixed: Set<String>, allowLocal: Boolean): DpiSet {
        if (needCurrent) {
            val cur = DpiStrategies.resolve(s, network)
            val p = DpiProxy(context); procs += p
            currentPort = p.start(cur, s.byeDpiSni, allowLocal); current = cur
        }
        for (id in fixed) {
            if (id == current?.id) { currentPort?.let { ports[id] = it }; continue }
            val st = DpiStrategies.byId(id, s) ?: continue
            val p = DpiProxy(context)
            if (!p.available(st.engine)) continue
            runCatching { ports[id] = p.start(st, s.byeDpiSni, allowLocal); procs += p }
        }
        return this
    }

    override fun close() { procs.forEach { it.close() }; procs.clear(); ports.clear(); currentPort = null; current = null }
}

/**
 * «Эволюция стратегий» (VLESS Card): the TSPU is tuned against the popular ByeDPI lines. Winners of a search get
 * mutants — other split / disorder positions, another fake TTL, a TLS record split added — and the best of all wins.
 * A mutant lives entirely in its id ("EVO#" + arguments), so it is remembered per network like any strategy.
 */
object DpiEvo {
    const val PREFIX = "EVO#"
    private const val SEP = "\u001f"
    private val POS_FLAGS = setOf("--split", "--disorder", "--tlsrec", "--oob", "--disoob", "--fake")
    private val NUM = Regex("-?\\d+")

    fun encode(args: List<String>): String = PREFIX + args.joinToString(SEP)
    fun decode(id: String): DpiStrategy? {
        val args = id.removePrefix(PREFIX).split(SEP).filter { it.isNotEmpty() }
        if (args.isEmpty() || args.size > 64) return null
        return DpiStrategy(id, "VLESS Card · выведенная: " + args.filter { it.startsWith("--") }.joinToString(" ") { it.removePrefix("--") }.take(40), DpiEngine.BYEDPI, args, own = true)
    }

    /** Jitters one position like "1+s", "3", "2:4:3+s", "-1+se": numbers move by 1-3, the sign and suffix stay. */
    private fun shift(v: String, rnd: kotlin.random.Random): String = NUM.replace(v) { m ->
        val n = m.value.toInt(); val d = rnd.nextInt(1, 4) * if (rnd.nextBoolean()) 1 else -1
        (if (n < 0) (n + d).coerceIn(-8, -1) else (n + d).coerceIn(1, 16)).toString()
    }

    fun mutate(base: DpiStrategy, rnd: kotlin.random.Random): DpiStrategy? {
        if (base.engine != DpiEngine.BYEDPI) return null
        val a = base.args.toMutableList()
        var changed = false
        for (i in 0 until a.size - 1) {
            when {
                a[i] in POS_FLAGS && rnd.nextInt(3) == 0 -> { a[i + 1] = shift(a[i + 1], rnd); changed = true }
                a[i] == "--ttl" && rnd.nextInt(2) == 0 -> { a[i + 1] = rnd.nextInt(3, 13).toString(); changed = true }
            }
        }
        if ("--tlsrec" !in a && rnd.nextInt(3) == 0) { a += listOf("--tlsrec", "1+s"); changed = true }
        if (!changed) { val i = a.indices.firstOrNull { a[it] in POS_FLAGS && it + 1 < a.size } ?: return null; a[i + 1] = shift(a[i + 1], rnd) }
        if (a == base.args) return null
        return decode(encode(a))
    }

    /** [n] distinct mutants of [parents] (best first get more). */
    fun breed(parents: List<DpiStrategy>, n: Int, seed: Long = System.nanoTime()): List<DpiStrategy> {
        val bye = parents.filter { it.engine == DpiEngine.BYEDPI }
        if (bye.isEmpty()) return emptyList()
        val rnd = kotlin.random.Random(seed)
        val out = LinkedHashMap<String, DpiStrategy>()
        var guard = 0
        while (out.size < n && guard++ < n * 10) {
            val p = bye[(rnd.nextDouble().let { it * it } * bye.size).toInt().coerceAtMost(bye.size - 1)]
            mutate(p, rnd)?.let { m -> if (parents.none { it.args == m.args }) out.putIfAbsent(m.id, m) }
        }
        return out.values.toList()
    }
}
