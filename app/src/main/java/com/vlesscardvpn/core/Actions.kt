package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.model.LinkParser
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Long-running user actions (subscriptions, tests, mask search). One at a time; progress for the UI. */
object Actions {
    data class Progress(val title: String = "", val done: Int = 0, val total: Int = 0, val running: Boolean = false, val message: String = "")
    val progress = MutableStateFlow(Progress())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }

    fun cancel() { job?.cancel(); progress.value = progress.value.copy(running = false, message = "Остановлено") }

    private fun launch(title: String, block: suspend () -> String) {
        if (job?.isActive == true) return
        progress.value = Progress(title, running = true)
        job = scope.launch {
            val msg = runCatching { withContext(Dispatchers.IO) { XrayCore.init(app) }; block() }.getOrElse { it.message ?: "Ошибка" }
            android.util.Log.i("E2E", "action[$title]: $msg")
            progress.value = progress.value.copy(running = false, message = msg)
        }
    }

    private fun step(done: Int, total: Int) { progress.value = progress.value.copy(done = done, total = total) }

    // ---------- subscriptions ----------
    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    private fun fetch(url: String): String? {
        val clients = buildList {
            add(http)
            val socks = Tunnel.socks
            if (socks != null && Tunnel.status.value.state == Tunnel.State.CONNECTED)
                add(http.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks.port))).build())
        }
        for (c in clients) for (u in Settings.mirrors(url)) {
            val body = runCatching {
                c.newCall(Request.Builder().url(u).header("User-Agent", "v2rayNG/1.10").build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()
            if (!body.isNullOrBlank() && LinkParser.parseMany(body).isNotEmpty()) return body
        }
        return null
    }

    fun refreshSubscriptions(then: (() -> Unit)? = null) = launch("Обновление подписок") { doRefresh(then) }

    private suspend fun doRefresh(then: (() -> Unit)? = null): String {
        val subs = Store.state.value.settings.subscriptions
        var ok = 0; var total = 0
        subs.forEachIndexed { i, url ->
            step(i, subs.size)
            val body = withContext(Dispatchers.IO) { fetch(url) }
            if (body != null) {
                val list = LinkParser.parseMany(body, url).take(MAX_PER_SUB)
                Store.replaceSource(url, list); ok++; total += list.size
            }
        }
        step(subs.size, subs.size)
        then?.invoke()
        return if (ok == 0) "Подписки не загрузились (нет сети или всё заблокировано)" else "Загружено серверов: $total из $ok подписок"
    }

    fun importText(text: String): Int {
        val list = LinkParser.parseMany(text, "manual")
        return Store.addServers(list)
    }

    // ---------- tests ----------
    /** Self-signed TLS servers: fetch and pin the certificate hash (Xray 26 has no allowInsecure). */
    suspend fun pinCertificates(servers: List<Server>): List<Server> = withContext(Dispatchers.IO) {
        servers.map { s ->
            if (!s.insecure || s.pcs.isNotEmpty() || s.security != "tls" && s.protocol != "hysteria2") return@map s
            val req = JSONObject().put("address", s.address).put("port", s.port).put("serverName", s.sni.ifEmpty { s.host.ifEmpty { s.address } }).put("timeoutMs", 5000)
            val res = runCatching {
                JSONObject(if (s.protocol == "hysteria2") Libv2ray.fetchQuicCertSha256(req.toString()) else Libv2ray.fetchTlsCertSha256(req.toString()))
            }.getOrNull()
            val sha = res?.optString("sha256").orEmpty()
            if (sha.isEmpty()) s else s.copy(pcs = sha).also { pinned ->
                Store.update { st -> st.copy(servers = st.servers.map { if (it.id == s.id) pinned else it }) }
            }
        }
    }

    /** Engines needed to test these masks: reuses the VPN's current one when connected. */
    private suspend fun dpiForTests(masks: Collection<Mask?>): Pair<DpiSet, Int?> {
        val st = Store.state.value.settings
        val running = Tunnel.byeDpiPort
        val needCur = running == null && masks.any { it?.dpi == Masks.CURRENT_DPI }
        val set = DpiSet(app)
        runCatching { set.start(st, Net.key(app), needCur, Masks.strategies(masks) - Masks.CURRENT_DPI, com.vlesscardvpn.BuildConfig.DEBUG) }
        return set to (running ?: set.currentPort)
    }

    /** TCP ping of all servers, then real test of the [realLimit] fastest. */
    fun testAll(onlySelected: Boolean = false, realLimit: Int = 150) = launch("Проверка серверов") {
        val st0 = Store.state.value
        val list = if (onlySelected) st0.selected else st0.servers
        if (list.isEmpty()) return@launch "Нет серверов — добавьте ссылку или обновите подписки"
        progress.value = progress.value.copy(title = "TCP-пинг")
        val done = AtomicInteger()
        val tcp = java.util.concurrent.ConcurrentHashMap<String, Int>()
        Tester.tcp(list) { s, ms ->
            tcp[s.id] = ms
            Store.setState(s.id) { it.copy(tcpMs = ms, realMs = if (ms == 0) 0 else it.realMs, checkedAt = System.currentTimeMillis()) }
            step(done.incrementAndGet(), list.size)
        }
        val alive = list.filter { (tcp[it.id] ?: 0) > 0 }.sortedBy { tcp[it.id] }.take(realLimit)
        val working = realTest(alive, "Проверка через Xray")
        "Работают: $working из ${alive.size} доступных по TCP (всего ${list.size})"
    }

    private suspend fun realTest(servers: List<Server>, title: String): Int {
        if (servers.isEmpty()) return 0
        progress.value = progress.value.copy(title = title, done = 0, total = servers.size)
        val pinned = pinCertificates(servers)
        val st = Store.state.value
        val variants = pinned.map { it to (Masks.byId(st.state(it).maskId)) }
        val (own, port) = dpiForTests(variants.map { it.second })
        val done = AtomicInteger(); val ok = AtomicInteger()
        try {
            Tester.real(variants, st.settings.testUrl, port, own.ports) { i, p ->
                val s = variants[i].first
                if (p.works) ok.incrementAndGet()
                Store.setState(s.id) {
                    it.copy(realMs = p.realMs, bigOk = p.bigOk, ytOk = p.ytOk, checkedAt = System.currentTimeMillis(),
                        okCount = it.okCount + if (p.works) 1 else 0, failCount = it.failCount + if (p.works) 0 else 1)
                }
                step(done.incrementAndGet(), variants.size)
            }
        } finally { own?.close() }
        return ok.get()
    }

    /** For each server, tries masks in [Masks.searchOrder] and keeps the fastest working one. */
    fun findMasks(servers: List<Server>, perServer: Int = 48) = launch("Подбор маскировки") {
        if (servers.isEmpty()) return@launch "Выберите серверы"
        val pinned = pinCertificates(servers)
        val probe = DpiProxy(app)
        val dpiOk = probe.available(DpiEngine.BYEDPI)
        val tpwsOk = probe.available(DpiEngine.TPWS)
        val order = { s: Server -> Masks.searchOrder(dpiOk, s).filter { tpwsOk || !it.dpi.startsWith("TPWS#") }.take(perServer) }
        val variants: List<Pair<Server, Mask?>> = pinned.flatMap { s -> order(s).map { s to it } }
        val (own, port) = dpiForTests(variants.map { it.second })
        val best = HashMap<String, Pair<Mask, Probe>>()
        val good = HashMap<String, MutableList<Pair<Mask, Int>>>()
        val done = AtomicInteger()
        try {
            Tester.real(variants, Store.state.value.settings.testUrl, port, own.ports, youtube = false, batch = 48, parallel = 8) { i, p ->
                val (s, m) = variants[i]
                synchronized(best) {
                    val cur = best[s.id]
                    if (p.works && m != null && (cur == null || p.realMs < cur.second.realMs)) best[s.id] = m to p
                    if (p.works && m != null) good.getOrPut(s.id) { mutableListOf() } += m to p.realMs
                }
                step(done.incrementAndGet(), variants.size)
            }
        } finally { own?.close() }
        pinned.forEach { s ->
            val b = best[s.id]
            Store.setState(s.id) {
                if (b == null) it.copy(realMs = 0, checkedAt = System.currentTimeMillis())
                else it.copy(maskId = b.first.id, realMs = b.second.realMs, bigOk = b.second.bigOk, checkedAt = System.currentTimeMillis(),
                    goodMasks = good[s.id].orEmpty().sortedBy { g -> g.second }.map { g -> g.first.id }.filter { g -> g != b.first.id }.take(6))
            }
        }
        "Маскировка найдена для ${best.size} из ${pinned.size} серверов (перебрано ${variants.size} вариантов)"
    }

    /** Selects the [n] fastest working servers (deselects others). */
    fun selectBest(n: Int = 5, among: Collection<Server>? = null): Int {
        var c = 0
        val ids = among?.map { it.id }?.toHashSet()
        Store.update { st ->
            val best = st.servers.filter { (ids == null || it.id in ids) && st.state(it).works }.sortedBy { st.state(it).realMs }.take(n).map { it.id }.toSet()
            c = best.size
            st.copy(states = st.servers.associate { s -> s.id to st.state(s).copy(selected = s.id in best) } )
        }
        return c
    }

    /** Current strategy only: does the YouTube page load through it? */
    fun testByeDpi() = launch("Проверка обхода DPI") {
        val st = Store.state.value.settings
        val strategy = DpiStrategies.resolve(st, Net.key(app))
        val b = DpiProxy(app)
        try {
            val port = b.start(strategy, st.byeDpiSni, allowLocal = com.vlesscardvpn.BuildConfig.DEBUG)
            val p = withContext(Dispatchers.IO) { Tester.probeDpi(port) }
            if (p.ok) "${strategy.label}: YouTube открылся за ${p.ms} мс" else "${strategy.label}: не работает (${p.error})"
        } finally { b.close() }
    }

    // ---------- DPI strategy search (как в ByeByeDPI «тест стратегий») ----------
    data class DpiResult(val ok: Boolean, val ms: Int, val error: String)
    /** Strategy id → result of the last search (this session). */
    val dpiResults = MutableStateFlow<Map<String, DpiResult>>(emptyMap())

    /**
     * Tries every strategy (custom line, the remembered one, 20 built-ins incl. zapret and own ones) through its
     * own local proxy, 4 at a time; a strategy works if the real YouTube page downloads. The best one in plan order
     * (gentlest first) is remembered for the current network and used automatically.
     */
    fun findDpi() = launch("Подбор обхода DPI") {
        val st = Store.state.value.settings
        val network = Net.key(app)
        val probe = DpiProxy(app)
        val plan = DpiStrategies.plan(st, network, probe.available(DpiEngine.TPWS)).filter { probe.available(it.engine) }
        if (plan.isEmpty()) return@launch "Обход DPI недоступен на этом устройстве (нужен Android 8+)"
        dpiResults.value = emptyMap()
        val done = AtomicInteger()
        step(0, plan.size)
        coroutineScope {
            val sem = Semaphore(4)
            plan.map { s ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val px = DpiProxy(app)
                        val r = try {
                            val port = px.start(s, st.byeDpiSni, allowLocal = com.vlesscardvpn.BuildConfig.DEBUG)
                            Tester.probeDpi(port, attempts = if (s.adaptive) 3 else 2)
                        } catch (e: Throwable) { Tester.DpiProbe(false, 0, 0, e.message ?: "не запустился") } finally { px.close() }
                        dpiResults.value = dpiResults.value + (s.id to DpiResult(r.ok, r.ms, r.error))
                        step(done.incrementAndGet(), plan.size)
                    }
                }
            }.awaitAll()
        }
        val res = dpiResults.value
        val working = plan.filter { res[it.id]?.ok == true }
        val best = working.firstOrNull()
        val zapretOk = working.count { it.engine == DpiEngine.TPWS }
        val ownOk = working.count { it.own }
        if (best != null) Store.update { a ->
            a.copy(settings = a.settings.copy(dpiRemembered = a.settings.dpiRemembered + (network to best.id) + (Settings.ANY_NETWORK to best.id)))
        }
        if (best == null) "Сеть «$network»: ни одна из ${plan.size} стратегий не открыла YouTube. Нужен сервер (режим «Серверы»)."
        else "Сеть «$network»: работают ${working.size} из ${plan.size} (zapret: $zapretOk, своих VLESS Card: $ownOk). Выбрана: ${best.label}"
    }

    // ---------- network diagnosis (DPI / white lists) ----------
    data class NetReport(val verdict: String = "", val details: String = "", val kind: Kind = Kind.NONE)
    enum class Kind { NONE, OPEN, DPI, WHITELIST, OFFLINE, PARTIAL }
    val netReport = MutableStateFlow(NetReport())

    /**
     * Direct checks (the app is outside the VPN): Russian sites vs foreign ones vs the real YouTube page.
     * Only Russian sites open → the operator is in "white list" mode; foreign open but YouTube cut → DPI throttling.
     */
    fun diagnoseNetwork() = launch("Проверка сети") {
        val client = http.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build()
        fun reach(url: String): Boolean = runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", Tester.UA).build()).execute().use { it.code in 100..499 }
        }.getOrDefault(false)
        fun bigYoutube(): Boolean = runCatching {
            client.newCall(Request.Builder().url(Tester.DPI_URL).header("User-Agent", Tester.UA).build()).execute().use { r ->
                val src = r.body!!.byteStream(); val buf = ByteArray(16384); var t = 0
                while (t < Tester.DPI_BYTES) { val n = src.read(buf); if (n < 0) break; t += n }
                t >= Tester.DPI_BYTES
            }
        }.getOrDefault(false)
        val ru = listOf("https://ya.ru/", "https://www.gosuslugi.ru/", "https://vk.com/")
        val foreign = listOf("https://www.gstatic.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace", "https://github.com/", "https://telegram.org/")
        step(0, ru.size + foreign.size + 1)
        val (ruOk, fOk, yt) = coroutineScope {
            val a = ru.map { async(Dispatchers.IO) { reach(it) } }
            val b = foreign.map { async(Dispatchers.IO) { reach(it) } }
            val c = async(Dispatchers.IO) { bigYoutube() }
            Triple(a.awaitAll().count { it }, b.awaitAll().count { it }, c.await())
        }
        step(ru.size + foreign.size + 1, ru.size + foreign.size + 1)
        val net = Net.key(app)
        val details = "Сеть «$net» · российские сайты: $ruOk/${ru.size} · зарубежные: $fOk/${foreign.size} · YouTube: ${if (yt) "открылся" else "не грузится"}"
        val r = when {
            ruOk == 0 && fOk == 0 -> NetReport("Нет интернета", details, Kind.OFFLINE)
            fOk == 0 -> NetReport("Белые списки: открываются только российские сайты. Нужны серверы из подписки «White-Lists» — они на разрешённых адресах.", details, Kind.WHITELIST)
            !yt -> NetReport("YouTube режут (DPI). Поможет обход DPI без сервера или любой рабочий сервер.", details, Kind.DPI)
            fOk < foreign.size -> NetReport("Часть зарубежных сайтов заблокирована — нужен сервер.", details, Kind.PARTIAL)
            else -> NetReport("Сеть без заметных ограничений.", details, Kind.OPEN)
        }
        netReport.value = r
        "${r.verdict} ($details)"
    }

    /** White-list mode: keep only servers from white-list subscriptions selected and test them. */
    fun whitelistServers() = launch("Серверы для белых списков") {
        val loaded = doRefresh()
        val wl = Store.state.value.servers.filter { it.source.contains("White", ignoreCase = true) }
        if (wl.isEmpty()) return@launch "$loaded. Серверов для белых списков нет."
        Store.update { st -> st.copy(states = st.servers.associate { s -> s.id to st.state(s).copy(selected = false) }) }
        val working = realTest(wl.take(150), "Серверы для белых списков")
        val n = selectBest(5, wl)
        "Белые списки: работают $working из ${minOf(wl.size, 150)}. Выбрано лучших: $n — жмите «Подключить»."
    }

    /** Panic button: stop everything and erase servers, settings and test results. */
    fun wipe() {
        cancel()
        Store.wipe(app)
        dpiResults.value = emptyMap(); netReport.value = NetReport()
        progress.value = Progress(message = "Все данные стёрты")
    }

    fun yn(b: Boolean?) = when (b) { true -> "да"; false -> "нет"; null -> "—" }

    const val MAX_PER_SUB = 600
}
