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
        val ok = AtomicInteger(); val total = AtomicInteger(); val done = AtomicInteger()
        step(0, subs.size)
        coroutineScope {
            val sem = Semaphore(6) // all sources at once instead of one by one (each may try 4 mirrors)
            subs.map { url ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val body = fetch(url)
                        if (body != null) {
                            val list = LinkParser.parseMany(body, url).take(MAX_PER_SUB)
                            Store.replaceSource(url, list); ok.incrementAndGet(); total.addAndGet(list.size)
                        }
                        step(done.incrementAndGet(), subs.size)
                    }
                }
            }.awaitAll()
        }
        step(subs.size, subs.size)
        if (ok.get() > 0) Store.update { it.copy(settings = it.settings.copy(lastSubRefresh = System.currentTimeMillis())) }
        then?.invoke()
        return if (ok.get() == 0) "Подписки не загрузились (нет сети или всё заблокировано)" else "Загружено серверов: ${total.get()} из ${ok.get()} подписок"
    }

    fun importText(text: String): Int {
        val list = LinkParser.parseMany(text, "manual")
        return Store.addServers(list)
    }

    // ---------- tests ----------
    /** Self-signed TLS servers: fetch and pin the certificate hash (Xray 26 has no allowInsecure). */
    suspend fun pinCertificates(servers: List<Server>): List<Server> = withContext(Dispatchers.IO) {
        val sem = Semaphore(16) // in parallel: subscriptions have many self-signed servers, 3 s each one by one was slow
        servers.map { s -> async { sem.withPermit {
            if (!s.insecure || s.pcs.isNotEmpty() || s.security != "tls" && s.protocol != "hysteria2") return@withPermit s
            val req = JSONObject().put("address", s.address).put("port", s.port).put("serverName", s.sni.ifEmpty { s.host.ifEmpty { s.address } }).put("timeoutMs", 3000)
            val res = runCatching {
                JSONObject(if (s.protocol == "hysteria2") Libv2ray.fetchQuicCertSha256(req.toString()) else Libv2ray.fetchTlsCertSha256(req.toString()))
            }.getOrNull()
            val sha = res?.optString("sha256").orEmpty()
            if (sha.isEmpty()) s else s.copy(pcs = sha).also { pinned ->
                Store.update { st -> st.copy(servers = st.servers.map { if (it.id == s.id) pinned else it }) }
            }
        } } }.awaitAll()
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
        doTestAll(if (onlySelected) st0.selected else st0.servers, realLimit)
    }

    private suspend fun doTestAll(list: List<Server>, realLimit: Int, big: Boolean = true): String {
        if (list.isEmpty()) return "Нет серверов — добавьте ссылку или обновите подписки"
        progress.value = progress.value.copy(title = "TCP-пинг")
        val done = AtomicInteger()
        val tcp = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val batch = StateBatch()
        Tester.tcp(list) { s, ms ->
            tcp[s.id] = ms
            batch.put(s.id) { it.copy(tcpMs = ms, realMs = if (ms == 0) 0 else it.realMs, checkedAt = System.currentTimeMillis()) }
            step(done.incrementAndGet(), list.size)
        }
        batch.flush()
        val alive = list.filter { (tcp[it.id] ?: 0) > 0 }.sortedBy { tcp[it.id] }.take(realLimit)
        val working = realTest(alive, "Проверка через Xray", big)
        return "Работают: $working из ${alive.size} доступных по TCP (всего ${list.size})"
    }

    private suspend fun realTest(servers: List<Server>, title: String, big: Boolean = true): Int {
        if (servers.isEmpty()) return 0
        progress.value = progress.value.copy(title = title, done = 0, total = servers.size)
        val pinned = pinCertificates(servers)
        val st = Store.state.value
        val net = Net.key(app)
        val variants = pinned.map { it to (Masks.byId(st.state(it).maskFor(net))) }
        val (own, port) = dpiForTests(variants.map { it.second })
        val done = AtomicInteger(); val ok = AtomicInteger()
        val states = StateBatch(25)
        try {
            Tester.real(variants, st.settings.testUrl, port, own.ports, batch = 48, parallel = 16, big = big) { i, p ->
                val s = variants[i].first
                if (p.works) ok.incrementAndGet()
                states.put(s.id) {
                    it.copy(realMs = p.realMs, bigOk = p.bigOk, ytOk = p.ytOk, tgOk = p.tgOk, checkedAt = System.currentTimeMillis(),
                        okCount = it.okCount + if (p.works) 1 else 0, failCount = it.failCount + if (p.works) 0 else 1)
                }
                step(done.incrementAndGet(), variants.size)
            }
        } finally { own?.close(); states.flush() }
        return ok.get()
    }

    /** For each server, tries masks in [Masks.searchOrder] and keeps the fastest working one (remembered per network). */
    fun findMasks(servers: List<Server>, perServer: Int = 48) = launch("Подбор маскировки") { doFindMasks(servers, perServer) }

    suspend fun doFindMasks(servers: List<Server>, perServer: Int = 48, enough: Int = 3, group: (Server) -> String = { it.id }, budgetMs: Long = Long.MAX_VALUE): String {
        val deadline = if (budgetMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + budgetMs
        if (servers.isEmpty()) return "Выберите серверы"
        val pinned = pinCertificates(servers)
        val probe = DpiProxy(app)
        val dpiOk = probe.available(DpiEngine.BYEDPI)
        val tpwsOk = probe.available(DpiEngine.TPWS)
        val cfg = Store.state.value.settings
        val net0 = Net.key(app)
        val stats = Store.state.value.maskStats[net0].orEmpty()
        // Learned order: masks that passed on this network (on any server) first, untested next, proven failures last.
        fun bucket(m: Mask): Int { val st = stats[m.id] ?: return 1; return if (st.ok > 0) 0 else if (st.fail >= 4) 2 else 1 }
        val order = { s: Server -> Masks.searchOrder(dpiOk, s, cfg.maskFamilies, cfg.maskFps).filter { tpwsOk || !it.dpi.startsWith("TPWS#") }
            .sortedWith(compareBy<Mask>({ bucket(it) }, { -(stats[it.id]?.takeIf { s -> s.ok > 0 }?.score ?: 0.0) })).take(perServer) }
        // Interleaved (1st mask of every server, then the 2nd…): parallel probes hit different servers, and a server
        // that already has [enough] working masks stops early instead of trying all of them.
        val lists = pinned.map { s -> order(s).map { s to it } }
        val variants: List<Pair<Server, Mask?>> = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { r -> lists.mapNotNull { it.getOrNull(r) } }
        val (own, port) = dpiForTests(variants.map { it.second })
        val best = HashMap<String, Pair<Mask, Probe>>()
        val good = HashMap<String, MutableList<Pair<Mask, Int>>>()
        val found = HashMap<String, Int>()
        val tried = mutableListOf<Triple<String, String, Boolean>>()
        val done = AtomicInteger()
        progress.value = progress.value.copy(title = "Подбор маскировки")
        try {
            Tester.real(variants, cfg.testUrl, port, own.ports, youtube = false, batch = 64, parallel = 12, attempts = 1,
                skip = { i -> System.currentTimeMillis() > deadline || synchronized(best) { (found[group(variants[i].first)] ?: 0) >= enough } }) { i, p ->
                val (s, m) = variants[i]
                synchronized(best) {
                    val cur = best[s.id]
                    if (p.works && m != null && (cur == null || p.realMs < cur.second.realMs)) best[s.id] = m to p
                    if (p.works && m != null) { good.getOrPut(s.id) { mutableListOf() } += m to p.realMs; found.merge(group(s), 1, Int::plus) }
                    if (m != null && p !== Tester.SKIPPED) tried += Triple(s.id, m.id, p.works)
                }
                step(done.incrementAndGet(), variants.size)
            }
        } finally { own.close() }
        val net = Net.key(app)
        // Failures of a server where nothing worked say nothing about the masks (the server may be dead).
        Store.recordMasks(net, tried.filter { (sid, _, ok) -> ok || good.containsKey(sid) }.map { it.second to it.third })
        pinned.forEach { s ->
            val b = best[s.id]
            Store.setState(s.id) {
                if (b == null) it.copy(realMs = 0, checkedAt = System.currentTimeMillis())
                else it.copy(maskId = b.first.id, netMasks = it.netMasks + (net to b.first.id), realMs = b.second.realMs, bigOk = b.second.bigOk,
                    checkedAt = System.currentTimeMillis(),
                    goodMasks = good[s.id].orEmpty().sortedBy { g -> g.second }.map { g -> g.first.id }.filter { g -> g != b.first.id }.take(8))
            }
        }
        return "Маскировка найдена для ${best.size} из ${pinned.size} серверов (перебрано ${variants.size} вариантов, сеть «$net»)"
    }

    /**
     * Self-healing after a failed check: the next good mask of every selected server (remembered for this network).
     * Returns false when there is nothing left to rotate to.
     */
    fun nextMasks(servers: List<Server>): Boolean {
        val net = Net.key(app)
        var changed = false
        Store.update { st ->
            st.copy(states = st.states + servers.mapNotNull { s ->
                val ss = st.state(s)
                val next = ss.goodMasks.firstOrNull() ?: return@mapNotNull null
                changed = true
                // the failing mask goes to the end of the list: it may work again later or on another network
                s.id to ss.copy(maskId = next, netMasks = ss.netMasks + (net to next), goodMasks = ss.goodMasks.drop(1) + ss.maskFor(net))
            })
        }
        return changed
    }

    /** Collects per-server results from parallel callbacks and applies them to the store in chunks. */
    class StateBatch(private val size: Int = 200) {
        private val pending = java.util.concurrent.ConcurrentHashMap<String, (com.vlesscardvpn.model.ServerState) -> com.vlesscardvpn.model.ServerState>()
        fun put(id: String, f: (com.vlesscardvpn.model.ServerState) -> com.vlesscardvpn.model.ServerState) { pending[id] = f; if (pending.size >= size) flush() }
        @Synchronized fun flush() {
            val snap = HashMap(pending); snap.keys.forEach { pending.remove(it) }
            Store.setStates(snap)
        }
    }

    // ---------- background optimisation (no buttons) ----------
    private fun unmetered(): Boolean = runCatching {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    /**
     * App start, VPN off: quietly gets everything ready so «Подключить» is instant — fresh subscriptions, tested servers,
     * masks for the reachable ones that don't open sites. Lighter on mobile data (fewer servers, no 256 KB download).
     */
    fun warmup() {
        val st = Store.state.value
        if (!st.settings.autoOptimize || job?.isActive == true || Tunnel.status.value.state != Tunnel.State.IDLE) return
        val now = System.currentTimeMillis()
        val fresh = st.servers.count { st.state(it).works && now - st.state(it).checkedAt < 3 * 3600_000L }
        val subsStale = st.settings.autoUpdateSubs && now - st.settings.lastSubRefresh > 12 * 3600_000L
        if (fresh >= 5 && !subsStale) return
        launch("Фоновая подготовка") {
            val wifi = unmetered()
            val notes = mutableListOf<String>()
            if (subsStale || Store.state.value.servers.isEmpty()) notes += doRefresh()
            val s1 = Store.state.value
            if (s1.servers.count { s1.state(it).works && now - s1.state(it).checkedAt < 3 * 3600_000L } < 5)
                notes += doTestAll(s1.servers, if (wifi) 120 else 40, big = wifi)
            val s2 = Store.state.value
            if (s2.servers.count { s2.state(it).works } < 3) {
                val cand = s2.servers.filter { s2.state(it).tcpMs > 0 && !s2.state(it).works }.sortedBy { s2.state(it).tcpMs }.take(if (wifi) 10 else 5)
                if (cand.isNotEmpty()) notes += doFindMasks(cand, 24)
            }
            "Готово к подключению: " + notes.joinToString("; ")
        }
    }

    /**
     * While connected, every ~20 min (TunnelService): re-tests the selected servers and the best other ones outside
     * the tunnel. If a selected server died or others are clearly faster, switches to the fastest and reconnects;
     * if nothing works, searches new masks. Returns true when the service should reconnect.
     */
    suspend fun optimize(): Boolean {
        val st = Store.state.value
        if (!st.settings.autoOptimize || job?.isActive == true) return false
        if (st.settings.mode == com.vlesscardvpn.model.Mode.BYEDPI) return false
        val wifi = unmetered()
        // Auto with nothing selected connects to the 5 best: compare against those.
        val sel = st.selected.ifEmpty { st.servers.filter { st.state(it).works }.sortedBy { st.state(it).realMs }.take(5) }
        val others = st.servers.filter { it !in sel && st.state(it).works }.sortedBy { st.state(it).realMs }.take(if (wifi) 12 else 6)
        val cand = (sel + others).distinctBy { it.id }
        if (cand.isEmpty()) return false
        val prev = progress.value
        realTest(cand, "Фоновая проверка", big = wifi)
        progress.value = prev
        val now = Store.state.value
        val selOk = sel.filter { now.state(it).works }
        val best = cand.filter { now.state(it).works }.sortedBy { now.state(it).realMs }
        if (best.isEmpty()) {
            val alive = now.servers.filter { now.state(it).tcpMs > 0 }.sortedBy { now.state(it).tcpMs }.take(8)
            if (alive.isNotEmpty()) doFindMasks(alive, 24)
            val after = Store.state.value
            return if (alive.any { after.state(it).works }) { selectBest(5, alive.filter { after.state(it).works }); true } else false
        }
        val median = selOk.map { now.state(it).realMs }.sorted().let { if (it.isEmpty()) Int.MAX_VALUE else it[it.size / 2] }
        val bestMs = now.state(best.first()).realMs
        val switch = selOk.size < sel.size || (median > bestMs * 1.6 && median - bestMs > 150)
        android.util.Log.i("E2E", "optimize sel=${sel.size} ok=${selOk.size} median=$median best=$bestMs switch=$switch")
        if (switch) selectBest(minOf(5, best.size), best)
        return switch && sel.map { it.id }.toSet() != Store.state.value.selected.map { it.id }.toSet()
    }

    // ---------- WARP ----------
    /**
     * «WARP»: registers up to 3 free Cloudflare accounts (one key each — the tester runs one WireGuard tunnel per key
     * at a time), 4 endpoints per account, then searches UDP noise masks for them and selects the working ones.
     */
    fun setupWarp(accounts: Int = 3) = launch("WARP") { doSetupWarp(accounts) }

    suspend fun doSetupWarp(accounts: Int = 3): String {
        progress.value = progress.value.copy(title = "WARP: регистрация")
        val accs = mutableListOf<Warp.Account>(); var err = ""
        var dpi: DpiSet? = null
        var plusErr = ""
        try {
        withContext(Dispatchers.IO) {
            repeat(accounts) { i ->
                step(i, accounts)
                var r = runCatching { Warp.register() }
                if (r.isFailure && dpi == null) {
                    // Cloudflare API blocked: retry through the DPI bypass picked for this network.
                    dpi = runCatching { DpiSet(app).start(Store.state.value.settings, Net.key(app), true, emptySet(), com.vlesscardvpn.BuildConfig.DEBUG) }.getOrNull()
                    dpi?.currentPort?.let { Warp.extraSocks = listOf(it); r = runCatching { Warp.register() } }
                }
                r.onSuccess { accs += it }.onFailure { err = it.message ?: it.javaClass.simpleName }
            }
        }
        // WARP+: bind a key to every account (own keys first, then the built-in public ones).
        val cfg = Store.state.value.settings
        val own = WarpKeys.parse(cfg.warpKeys)
        if (own.isNotEmpty() || cfg.warpBuiltinKeys) {
            progress.value = progress.value.copy(title = "WARP: ключ WARP+")
            withContext(Dispatchers.IO) {
                accs.indices.forEach { i ->
                    val (key, e) = Warp.upgrade(accs[i], own, cfg.warpBuiltinKeys)
                    if (key != null) accs[i] = accs[i].copy(plus = true) else plusErr = e
                }
            }
        }
        } finally { dpi?.close(); Warp.extraSocks = emptyList() }
        val plus = accs.count { it.plus }
        android.util.Log.i("E2E", "warp accounts=${accs.size} plus=$plus err=$err plusErr=$plusErr")
        if (accs.isEmpty()) return "WARP: регистрация не удалась ($err). Попробуйте после подключения к любому серверу или ByeDPI."
        val list = accs.flatMapIndexed { i, a -> Warp.servers(a, Warp.endpointsFor(i)) }
        Store.replaceSource(Warp.SOURCE, list)
        // one working endpoint per account is enough (only one tunnel per key can run at a time anyway)
        // 10 most likely masks, 2.5 min at most: an account whose endpoints are all dead must not stall the search
        val res = doFindMasks(list, perServer = 10, enough = 1, group = { it.secret }, budgetMs = 150_000)
        val st = Store.state.value
        val ok = list.filter { st.state(it).works }
        android.util.Log.i("E2E", "warp servers=${list.size} working=${ok.size} masks=${ok.map { st.state(it).maskId }}")
        // one endpoint per account: two tunnels with the same key at once would roam and stall
        if (ok.isNotEmpty()) selectBest(3, ok.sortedBy { st.state(it).realMs }.distinctBy { it.secret })
        return if (ok.isEmpty()) "WARP: аккаунтов ${accs.size}, но ни одна точка входа не ответила ($res). WireGuard в этой сети, похоже, режут."
        else "WARP готов: рабочих точек входа ${ok.size} (по одной на аккаунт, всего ${list.size}), выбраны. " +
            (if (plus > 0) "WARP+: $plus из ${accs.size} аккаунтов. " else if (plusErr.isNotEmpty()) "WARP+ не подключился ($plusErr) — обычный WARP. " else "") +
            "Нажмите «Подключить»."
    }

    // ---------- Auto mode ----------
    /**
     * «Авто»: fresh subscriptions → test → masks for the best candidates → DPI search when no server works →
     * the 5 fastest selected. Then [onReady] connects (servers, or ByeDPI if none passed).
     */
    fun autoConnect(onReady: () -> Unit) {
        val busy = job
        if (busy?.isActive == true) { scope.launch { busy.join(); autoPrepare(onReady) }; return }
        autoPrepare(onReady)
    }

    private fun autoPrepare(onReady: () -> Unit) = launch("Авто-настройка") {
        val now = System.currentTimeMillis()
        val st0 = Store.state.value
        val notes = mutableListOf<String>()
        if (st0.servers.isEmpty() || st0.settings.autoUpdateSubs && now - st0.settings.lastSubRefresh > 12 * 3600_000L) {
            progress.value = progress.value.copy(title = "Авто: обновляю подписки"); notes += doRefresh()
        }
        fun fresh() = Store.state.value.let { st -> st.servers.filter { st.state(it).works && now - st.state(it).checkedAt < 6 * 3600_000L } }
        if (fresh().size < 3 && Store.state.value.servers.isNotEmpty()) {
            progress.value = progress.value.copy(title = "Авто: проверяю серверы")
            notes += doTestAll(Store.state.value.servers, 80)
        }
        if (fresh().size < 3) {
            val st = Store.state.value
            val candidates = st.servers.filter { st.state(it).tcpMs > 0 && !st.state(it).works }.sortedBy { st.state(it).tcpMs }.take(10)
            if (candidates.isNotEmpty()) notes += doFindMasks(candidates, 24)
        }
        val working = fresh()
        if (working.isEmpty()) {
            val st = Store.state.value.settings
            if (st.dpiRemembered[Net.key(app)] == null) { progress.value = progress.value.copy(title = "Авто: подбираю обход DPI"); notes += doFindDpi() }
        }
        val n = selectBest(5, working)
        withContext(Dispatchers.Main) { onReady() }
        if (n > 0) "Авто: выбрано $n лучших серверов — подключаюсь" else "Авто: рабочих серверов нет — подключаюсь через обход DPI без сервера"
    }

    /** Subscriptions older than 12 h are refreshed in the background on app start. */
    fun maybeAutoRefresh() {
        val st = Store.state.value.settings
        if (st.autoUpdateSubs && System.currentTimeMillis() - st.lastSubRefresh > 12 * 3600_000L) refreshSubscriptions()
    }

    /** Selects the [n] fastest working servers (deselects others). */
    fun selectBest(n: Int = 5, among: Collection<Server>? = null): Int {
        var c = 0
        val ids = among?.map { it.id }?.toHashSet()
        Store.update { st ->
            // one WireGuard tunnel per key (WARP accounts): two with the same key would steal the session from each other
            val best = st.servers.filter { (ids == null || it.id in ids) && st.state(it).works }.sortedBy { st.state(it).realMs }
                .distinctBy { if (it.protocol == "wireguard") "wg:" + it.secret else it.id }.take(n).map { it.id }.toSet()
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
    fun findDpi() = launch("Подбор обхода DPI") { doFindDpi() }

    private suspend fun doFindDpi(): String {
        val st = Store.state.value.settings
        val network = Net.key(app)
        val probe = DpiProxy(app)
        val plan = DpiStrategies.plan(st, network, probe.available(DpiEngine.TPWS)).filter { probe.available(it.engine) }
        if (plan.isEmpty()) return "Обход DPI недоступен на этом устройстве"
        dpiResults.value = emptyMap()
        val done = AtomicInteger()
        step(0, plan.size)
        coroutineScope {
            val sem = Semaphore(8)
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
        val xrOk = working.count { it.engine == DpiEngine.XRAY }
        if (best != null) Store.update { a ->
            a.copy(settings = a.settings.copy(dpiRemembered = a.settings.dpiRemembered + (network to best.id) + (Settings.ANY_NETWORK to best.id)))
        }
        return if (best == null) "Сеть «$network»: ни одна из ${plan.size} стратегий не открыла YouTube. Нужен сервер (режим «Серверы»)."
        else "Сеть «$network»: работают ${working.size} из ${plan.size} (zapret: $zapretOk, своих VLESS Card: $ownOk, из них Xray: $xrOk). Выбрана: ${best.label}"
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
        val wl = Store.state.value.servers.filter { com.vlesscardvpn.model.Subs.isWhitelist(it.source) }
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

    const val MAX_PER_SUB = 1000
}
