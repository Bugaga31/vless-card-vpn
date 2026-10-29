package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.model.LinkParser
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.MaskLab
import com.vlesscardvpn.xray.Masks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ensureActive
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
    private val cloudRefreshing = java.util.concurrent.atomic.AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Bg.cpu + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("VLESS", "background job failed", e) })
    /** Only "is something running": buttons don't redraw the whole screen on every progress step. */
    val running: kotlinx.coroutines.flow.StateFlow<Boolean> = progress.map { it.running }.distinctUntilChanged()
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    private var job: Job? = null
    private lateinit var app: Context
    private val probeMemory = ProbeMemory()
    private var requestedSourceJob: Job? = null
    private var requestedSourceAttempt = -60000L

    fun init(context: Context) {
        app = context.applicationContext
        maybeLoadRequestedSource()
        runCatching { Brain.load(app) } // load before the first probe can initialize fresh weights
        // 1.0.81–1.0.83 left a mask neural network and maybe a 0.5–1.8 GB language model on the phone: removed, space freed
        runCatching { java.io.File(app.filesDir, "mask_brain.json").delete(); java.io.File(app.filesDir, "llm").deleteRecursively() }
    }

    fun cancelForNetworkChange() {
        autoGeneration.incrementAndGet()
        job?.cancel()
        progress.value = progress.value.copy(running = false, message = "Сеть изменилась — старые проверки отменены")
    }

    fun cancel() { job?.cancel(); progress.value = progress.value.copy(running = false, message = "Остановлено") }

    /** One in-flight public refresh; never block the connect button on mirror timeouts. */
    fun refreshPublicCloud(foreground: Boolean = false) {
        if (!Report.enabled(app) || !cloudRefreshing.compareAndSet(false,true)) return
        scope.launch(Bg.io) {
            try { CloudControl.refresh(app, foreground); CloudSpeed.refresh(app, foreground) }
            finally { cloudRefreshing.set(false) }
        }
    }

    private fun launch(title: String, block: suspend () -> String) {
        if (job?.isActive == true) return
        progress.value = Progress(title, running = true)
        job = scope.launch {
            val msg = Store.busy { runCatching { withContext(Dispatchers.IO) { XrayCore.init(app) }; block() }.getOrElse {
                if (com.vlesscardvpn.BuildConfig.DEBUG) android.util.Log.i("E2E", ConnectDiagnostics.describe("action","action",it))
                if (it is kotlinx.coroutines.CancellationException) "Остановлено" else Errors.human(it).replaceFirstChar { c -> c.uppercase() }
            } }
            android.util.Log.i("E2E", "action[$title]: $msg")
            progress.value = progress.value.copy(running = false, message = msg)
        }
    }

    @Volatile private var lastStepAt = 0L
    /**
     * Progress from parallel probes: at most ~6 updates a second (and always the last one). Hundreds of results per
     * second used to recompose the whole screen for every single server — that was the lag during big checks.
     */
    private fun step(done: Int, total: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (done < total && now - lastStepAt < 160) return
        lastStepAt = now
        progress.value = progress.value.copy(done = done, total = total)
    }

    // ---------- subscriptions ----------
    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    /** Body + what the subscription says about itself (headers). */
    private fun fetchFull(url: String): Pair<String, com.vlesscardvpn.model.SubInfo?>? {
        val clients = buildList {
            add(http)
            val socks = Tunnel.socks
            if (socks != null && Tunnel.status.value.state == Tunnel.State.CONNECTED)
                add(http.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socks.port))).build())
        }
        for (c in clients) for (u in Settings.mirrors(url)) {
            var info: com.vlesscardvpn.model.SubInfo? = null
            val body = runCatching {
                c.newCall(Request.Builder().url(u).header("User-Agent", "v2rayNG/1.10").build()).execute().use { r ->
                    info = com.vlesscardvpn.model.SubInfo.parse(r.header("subscription-userinfo"), r.header("profile-title"))
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
            if (!body.isNullOrBlank() && LinkParser.parseMany(body).isNotEmpty()) return body to info
        }
        return null
    }

    fun refreshSubscriptions(then: (() -> Unit)? = null) = launch("Обновление подписок") { doRefresh(then) }

    private suspend fun doRefresh(then: (() -> Unit)? = null, firstReady: (suspend (List<Server>) -> Unit)? = null): String {
        val subs = Store.state.value.settings.subscriptions
        val ok = AtomicInteger(); val total = AtomicInteger(); val done = AtomicInteger()
        val first = java.util.concurrent.atomic.AtomicBoolean()
        step(0, subs.size)
        coroutineScope {
            val sem = Semaphore(6) // all sources at once instead of one by one (each may try 4 mirrors)
            subs.map { url ->
                async(Bg.io) {
                    sem.withPermit {
                        val got = fetchFull(url); val body = got?.first
                        got?.second?.let { inf -> Store.update { it.copy(settings = it.settings.copy(subInfo = it.settings.subInfo + (url to inf))) } }
                        if (body != null) {
                            val list = LinkParser.parseMany(body, url).take(MAX_PER_SUB)
                            Store.replaceSource(url, list); ok.incrementAndGet(); total.addAndGet(list.size)
                            if (list.isNotEmpty() && firstReady != null && first.compareAndSet(false, true)) firstReady(list)
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

    /** vcmask:// links in pasted text → «Мои маскировки». Returns how many new ones were added. */
    fun importMasks(text: String): Int {
        val found = com.vlesscardvpn.xray.MyMasks.parseLinks(text)
        if (found.isEmpty()) return 0
        var added = 0
        Store.update { st ->
            val have = com.vlesscardvpn.xray.MyMasks.load(st.settings.myMasks).map { it.id }.toSet()
            val fresh = found.filter { it.id !in have }; added = fresh.size
            st.copy(settings = st.settings.copy(myMasks = st.settings.myMasks + fresh.map { com.vlesscardvpn.xray.MyMasks.store(it) }))
        }
        return added
    }

    /** vcnet:// links («Настройка сети» from a friend) → applied to the current network. Null = no such link in [text]. */
    fun importNetProfile(text: String): String? {
        val found = NetProfile.parseLinks(text).filter { !it.isEmpty }
        if (found.isEmpty()) return null
        val net = Net.key(app)
        var dpi = 0; var masks = 0
        found.forEach { p -> var got = NetProfile.Applied(0, 0); Store.update { st -> NetProfile.apply(st, p, net).let { (out, a) -> got = a; out } }; dpi += got.dpi; masks += got.masks }
        Store.saveNow()
        return "Настройка сети «${found.first().net}» применена к «$net»: обход DPI — $dpi, маскировок — $masks. Они пробуются первыми"
    }

    /** «Поделиться настройкой сети»: a vcnet:// link of the current network, null when nothing was learned yet. */
    fun shareNetProfile(): String? = NetProfile.build(Store.state.value, Net.key(app)).takeIf { !it.isEmpty }?.let { NetProfile.encode(it) }

    /** What the app knows about the current network (home screen card). */
    data class NetInfo(val net: String = "", val dpi: String = "", val dpiCount: Int = 0, val masks: Int = 0)
    fun netInfo(): NetInfo {
        val st = Store.state.value; val s = st.settings; val net = Net.key(app); val fam = Net.family(net)
        val known = s.dpiRemembered[net] ?: s.dpiRemembered[fam]
        return NetInfo(net, if (known != null || s.dpiStrategy != Settings.DPI_AUTO) DpiStrategies.resolve(s, net).label else "",
            (s.dpiRanking[net] ?: s.dpiRanking[fam]).orEmpty().size,
            (st.maskStats[net] ?: st.maskStats[fam]).orEmpty().count { it.value.ok > 0 })
    }

    // ---------- speed test through the running VPN ----------
    data class Speed(val running: Boolean = false, val text: String = "", val mbps: Double = 0.0)
    val speed = MutableStateFlow(Speed())
    fun speedTest() {
        if (speed.value.running) return
        val port = Tunnel.socks?.port ?: run { speed.value = Speed(text = "Сначала подключитесь"); return }
        speed.value = Speed(running = true, text = "Измеряю скорость…")
        scope.launch(Bg.io) {
            val r = runCatching { Tester.speed(port) }.getOrNull()
            speed.value = if (r == null || r.mbps <= 0) Speed(text = "Не удалось измерить: загрузка не идёт")
                else Speed(text = "Скорость: " + "%.1f".format(r.mbps) + " Мбит/с · пинг ${r.pingMs} мс" + (if (r.mbps < 2) " — медленно, попробуйте «Обновить и проверить»" else ""), mbps = r.mbps)
        }
    }

    // ---------- «Сайт не открывается?» ----------
    data class SiteCheck(val running: Boolean = false, val host: String = "", val verdict: String = "", val details: String = "", val good: Boolean = false, val suggest: String = "")
    val siteCheck = MutableStateFlow(SiteCheck())
    fun checkSite(input: String) {
        if (siteCheck.value.running) return
        val host = Settings.host(input) ?: run { siteCheck.value = SiteCheck(verdict = "Введите адрес сайта, например rutracker.org"); return }
        val port = Tunnel.socks?.port ?: run {
            siteCheck.value = SiteCheck(host = host, verdict = "Сначала подключитесь к рабочему VLESS-серверу", details = "Проверки без сервера отключены.")
            return
        }
        siteCheck.value = SiteCheck(running = true, host = host, verdict = "Проверяю через VLESS…")
        scope.launch(Bg.io) {
            siteCheck.value = siteVerdict(host, Tester.site("https://$host/", port))
        }
    }

    private fun word(p: Tester.SiteProbe) = when (p.result) {
        "ok" -> "открывается (${p.ms} мс)"; "timeout" -> "зависает"; "reset" -> "соединение сбрасывают"
        "dns" -> "имя не находится через VLESS"; "tls" -> "ошибка шифрования"; "denied" -> "сайт отказал (${p.code})"; else -> "не открывается"
    }

    fun siteVerdict(host: String, viaServer: Tester.SiteProbe): SiteCheck {
        val details = "Через выбранный VLESS-сервер: ${word(viaServer)}"
        return when {
            viaServer.ok -> SiteCheck(host = host, verdict = "Сайт открывается через VLESS", details = details, good = true)
            viaServer.result == "denied" -> SiteCheck(host = host, verdict = "Сайт ответил отказом через VLESS; смена маршрута на прямой отключена", details = details)
            viaServer.result == "dns" -> SiteCheck(host = host, verdict = "Сайт не найден через DNS выбранного сервера", details = details)
            else -> SiteCheck(host = host, verdict = "Сайт не открылся через выбранный сервер — попробуйте «Авто» или другой VLESS-сервер", details = details)
        }
    }

    /** "осталось 4.2 ГБ из 50 ГБ · до 15.10.2025" */
    fun subLine(i: com.vlesscardvpn.model.SubInfo, nowSec: Long = System.currentTimeMillis() / 1000): String = listOfNotNull(
        if (i.total > 0) "осталось ${bytes(i.left)} из ${bytes(i.total)}" else if (i.used > 0) "использовано ${bytes(i.used)}" else null,
        if (i.expire > 0) (if (i.expire < nowSec) "истекла " else "до ") + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US).format(java.util.Date(i.expire * 1000)) else null,
    ).joinToString(" · ")

    /** «Проверить популярные»: the usual suspects at once, directly and through the VPN. */
    val POPULAR = listOf("YouTube" to "youtube.com", "Instagram" to "instagram.com", "Telegram" to "web.telegram.org", "WhatsApp" to "web.whatsapp.com",
        "Discord" to "discord.com", "ChatGPT" to "chatgpt.com", "X (Twitter)" to "x.com", "Facebook" to "facebook.com", "Spotify" to "open.spotify.com",
        "LinkedIn" to "linkedin.com", "Rutracker" to "rutracker.org", "Gemini" to "gemini.google.com")
    data class Popular(val running: Boolean = false, val rows: List<Pair<String, SiteCheck>> = emptyList(), val summary: String = "")
    val popular = MutableStateFlow(Popular())
    fun checkPopular() {
        if (popular.value.running) return
        val port = Tunnel.socks?.port ?: run {
            popular.value = Popular(summary = "Проверки без сервера отключены. Сначала подключитесь к VLESS.")
            return
        }
        popular.value = Popular(running = true, summary = "Проверяю ${POPULAR.size} сервисов через VLESS…")
        scope.launch(Bg.io) {
            val gate = Semaphore(4)
            val rows = coroutineScope {
                POPULAR.map { (title, host) -> async {
                    gate.withPermit { title to siteVerdict(host, Tester.site("https://$host/", port)) }
                } }.awaitAll()
            }
            popular.value = Popular(rows = rows, summary = popularSummary(rows, true))
        }
    }

    fun popularSummary(rows: List<Pair<String, SiteCheck>>, vpnOn: Boolean): String {
        if (!vpnOn) return "Проверки без сервера отключены. Сначала подключитесь к VLESS."
        val bad = rows.filter { !it.second.good }.map { it.first }
        return if (bad.isEmpty()) "Через выбранный VLESS-сервер доступны все сервисы (${rows.size})"
            else "Через выбранный VLESS-сервер не прошли: " + bad.joinToString(", ") + ". Попробуйте «Авто» или другой сервер."
    }

    fun routeSite(host: String, where: String) {
        if (where !in setOf("", "vpn")) return
        Store.update { st ->
            val s = st.settings
            st.copy(settings = s.copy(alwaysVpn = (s.alwaysVpn - host).let { if (where == "vpn") it + host else it }, alwaysDirect = emptyList()))
        }
        siteCheck.value = siteCheck.value.copy(suggest = "")
    }

    // ---------- backup (Backup.kt) ----------
    fun backup(): String = Backup.pack(Store.state.value)
    /** Restores a vcbackup:// link. Null = no backup in [text]. */
    fun importBackup(text: String): String? {
        val b = Backup.find(text) ?: return null
        val st = Backup.unpack(b) ?: return "Копия повреждена (скопирована не целиком?)"
        var added = 0
        Store.update { cur -> val (m, n) = Backup.merge(cur, st); added = n; m }
        return "Восстановлено: настройки, серверов $added"
    }

    /** Everything that can be pasted: backup, network setup, servers, masks. Returns what happened (for a toast). */
    fun importAny(text: String): String {
        importBackup(text)?.let { return it }
        importNetProfile(text)?.let { return it }
        val n = importText(text); val nm = importMasks(text)
        return when {
            n == 0 && nm == 0 -> "Ссылки не найдены или уже есть"
            nm == 0 -> "Добавлено серверов: $n"
            n == 0 -> "Добавлено маскировок: $nm (Настройки → Мои маскировки)"
            else -> "Добавлено серверов: $n, маскировок: $nm"
        }
    }

    /** «В буфере ссылка — добавить?»: what a copied text would add ("" = nothing new). */
    fun clipSummary(text: String): String {
        if (Backup.find(text) != null) return "резервная копия"
        if (text.contains(NetProfile.SCHEME)) return "настройка сети"
        if (text.contains("vcmask://")) return "маскировка"
        val known = Store.state.value.servers.map { it.id }.toHashSet()
        val n = runCatching { LinkParser.parseMany(text, "manual").count { it.id !in known } }.getOrDefault(0)
        return if (n == 0) "" else if (n == 1) "сервер" else "серверов: $n"
    }
    val clipOffer = MutableStateFlow<Pair<String, String>?>(null) // text to what

    fun importText(text: String): Int {
        val list = LinkParser.parseMany(text, "manual")
        return Store.addServers(list)
    }

    // ---------- tests ----------
    /** Self-signed TLS servers: fetch and pin the certificate hash (Xray 26 has no allowInsecure). */
    suspend fun pinCertificates(servers: List<Server>): List<Server> = withContext(Bg.io) {
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

    /** TCP ping of every server, then a bounded real test of the fastest reachable servers. */
    fun testAll(onlySelected: Boolean = false, realLimit: Int = 150) = launch("Проверка серверов") {
        val snapshot = Store.state.value
        val targets = if (onlySelected) snapshot.selected else snapshot.servers
        if (targets.isEmpty()) return@launch if (onlySelected) "Сначала выберите серверы для проверки" else "Нет серверов — добавьте ссылку или обновите подписки"
        val limits = ManualServerCheckPolicy.limits(PhonePower.refresh(app))
        doTestAll(targets, realLimit, tcpParallel = limits.tcpParallel, realParallel = limits.realParallel, realBatch = limits.batch)
    }

    private suspend fun doTestAll(
        list: List<Server>,
        realLimit: Int,
        big: Boolean = true,
        tcpParallel: Int = 192,
        realParallel: Int = 64,
        realBatch: Int = 64,
    ): String {
        if (list.isEmpty()) return "Нет серверов — добавьте ссылку или обновите подписки"
        progress.value = progress.value.copy(title = "TCP-пинг")
        val done = AtomicInteger()
        val tcp = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val batch = StateBatch()
        Tester.tcp(list, parallel = tcpParallel, timeoutMs = 2000) { s, ms ->
            tcp[s.id] = ms
            batch.put(s.id) { it.copy(tcpMs = ms, realMs = if (ms == 0) 0 else it.realMs, checkedAt = System.currentTimeMillis()) }
            step(done.incrementAndGet(), list.size)
        }
        batch.flush()
        // which of the TCP-reachable ones get the (slower) real check: the mini model's favourites first, then by ping
        val alive = list.filter { (tcp[it.id] ?: 0) > 0 }.let { l -> runCatching { Brain.load(app) }; val now = Store.state.value
            if (Brain.seen >= 50 && l.size > realLimit) Brain.order(Net.key(app), l) { now.state(it) } else l.sortedBy { tcp[it.id] } }
            .let { ServerChoice.diverse(it, realLimit) }
        // one attempt for all (fast), a second one only for those that failed although TCP answers
        var working = realTest(alive, "Проверка через Xray", big, attempts = 1, parallel = realParallel, batch = realBatch)
        val again = Store.state.value.let { s -> alive.filter { !s.state(it).works } }
        if (again.isNotEmpty() && again.size <= 6) working += realTest(again, "Повторная проверка", big, attempts = 2, parallel = realParallel, batch = realBatch)
        return "Работают: $working из ${alive.size} доступных по TCP (всего ${list.size})"
    }

    private suspend fun realTest(servers: List<Server>, title: String, big: Boolean = true, attempts: Int = 2, parallel: Int = 32, youtube: Boolean = true,
                                 telegram: Boolean = youtube, calls: Boolean = false, stopAfter: Int = Int.MAX_VALUE, budgetMs: Long = 0, onFirst: ((Server) -> Unit)? = null, onSuccess: ((Server) -> Unit)? = null,
                                 batch: Int = 64, foreground: Boolean = false, onAttempt: ((Server)->Unit)? = null, onObservation: ((Probe)->Unit)? = null): Int {
        if (servers.isEmpty()) return 0
        val plan = PhonePower.refresh(app)
        val slots = PowerPolicy.cap(parallel, if (foreground) plan.foregroundRealParallel else plan.realParallel).coerceAtMost(servers.size)
        progress.value = progress.value.copy(title = title, done = 0, total = servers.size,
            message = if (foreground) "HTTPS: ${servers.size} в волне · одновременно до $slots" +
                (if (slots < minOf(parallel, servers.size)) " · ${plan.reason}" else "") else progress.value.message)
        val net = Net.key(app)
        val underlying = Net.underlying(app)?.toString()
        val pinned = pinCertificates(servers)
        if (Net.key(app) != net || Net.underlying(app)?.toString() != underlying) return 0
        val st = Store.state.value
        val variants = pinned.map { it to (Masks.byId(st.state(it).maskFor(net))) }
        val sampleStartedAt=System.currentTimeMillis()
        val (own, port) = dpiForTests(variants.map { it.second })
        val done = AtomicInteger(); val ok = AtomicInteger()
        val states = StateBatch(200)
        val firstWinner = java.util.concurrent.atomic.AtomicReference<Server?>(null)
        try {
            Tester.real(variants, st.settings.testUrl, port, own.ports, batch = if (onFirst != null) ConnectPolicy.FIRST else batch, parallel = parallel, big = big, youtube = youtube, telegram = telegram, calls = calls, foreground = foreground, attempts = attempts, requireTelegram = st.settings.mode == Mode.AUTO && telegram, stopAfter = stopAfter, budgetMs = budgetMs, onStarted = { i ->
                if(Net.key(app)==net && Net.underlying(app)?.toString()==underlying) onAttempt?.invoke(variants[i].first)
            }) { i, p ->
                if (p == Tester.SKIPPED) return@real
                // Do not train the old profile with a result completed after Wi-Fi/cellular handoff.
                if (Net.key(app) != net || Net.underlying(app)?.toString() != underlying) return@real
                val s = variants[i].first
                if(Store.state.value.state(s).maskFor(net)!=st.state(s).maskFor(net)) return@real
                onObservation?.invoke(p)
                val speedSample = ServerChoice.acceptsSpeedSample(p.kbps, parallel)
                if (telegram && p.works) TelegramRouteMemory.record(net,s.id,st.state(s).maskFor(net),p.tgOk,System.currentTimeMillis())
                val routeReady = p.works && (st.settings.mode != Mode.AUTO || !telegram || AutoLatencyPolicy.accepts(p))
                val firstSuccess = routeReady && ok.incrementAndGet() == 1
                if (routeReady) onSuccess?.invoke(s)
                // A deliberately short attempt is inconclusive when it times out; confirm with the normal budget.
                if (!p.works && budgetMs in 1 until ConnectPolicy.QUICK_BUDGET_MS) return@real
                probeMemory.record(net, s.id, st.state(s).maskFor(net), p.works, System.currentTimeMillis())
                runCatching { Brain.learn(net, s, st.state(s), p.works) }
                states.put(s.id) {
                    if (Net.key(app) != net || Net.underlying(app)?.toString() != underlying || it.maskFor(net)!=st.state(s).maskFor(net) || it.checkedAt>sampleStartedAt) it else
                    it.copy(netEvidence = (it.netEvidence + ((net + "|" + st.state(s).maskFor(net)) to it.evidenceFor(net).observe(p.works, p.realMs, System.currentTimeMillis()))).entries.sortedByDescending { e -> e.value.at }.take(8).associate { e -> e.toPair() }, realMs = p.realMs, bigOk = p.bigOk ?: it.bigOk?.takeIf { old -> old }, ytOk = if (youtube) p.ytOk else if (telegram) null else it.ytOk, tgOk = if (telegram) p.tgOk else if (youtube) null else it.tgOk, tgMs = if(telegram) p.tgMs else if(youtube) 0 else it.tgMs, checkedAt = System.currentTimeMillis(), kbps = if (speedSample) p.kbps else it.kbps,
                        serviceNetwork = if ((youtube || telegram) && p.works) net else it.serviceNetwork,
                        serviceMask = if ((youtube || telegram) && p.works) st.state(s).maskFor(net) else it.serviceMask,
                        serviceCheckedAt = if ((youtube || telegram) && p.works) System.currentTimeMillis() else it.serviceCheckedAt,
                        speedNetwork = if (speedSample) net else it.speedNetwork,
                        speedMask = if (speedSample) st.state(s).maskFor(net) else it.speedMask,
                        speedCheckedAt = if (speedSample) System.currentTimeMillis() else it.speedCheckedAt,
                        okCount = it.okCount + if (p.works) 1 else 0, failCount = it.failCount + if (p.works) 0 else 1)
                }
                if (firstSuccess && onFirst != null) firstWinner.compareAndSet(null, s)
                step(done.incrementAndGet(), variants.size)
            }
        } finally { own?.close(); states.flush(); runCatching { Brain.save(app) } }
        // stopAfter=1 already cancels the remaining probes. Finish their cleanup and
        // commit all results before starting a VPN core; do not race two core lifecycles.
        firstWinner.get()?.let { s ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val committed = Store.state.value.state(s).let {it.works && it.maskFor(net)==st.state(s).maskFor(net)}
            val sameNetwork = Net.key(app) == net && Net.underlying(app)?.toString() == underlying
            if (com.vlesscardvpn.BuildConfig.DEBUG) android.util.Log.i("E2E",
                "auto first_probe committed=$committed same_network=$sameNetwork test_cleanup=true")
            if (committed && sameNetwork) onFirst?.invoke(s)
        }
        return ok.get()
    }

    /** For each server, tries masks in [Masks.searchOrder] and keeps the fastest working one (remembered per network). */
    fun findMasks(servers: List<Server>, perServer: Int = 48) = launch("Подбор маскировки") { doFindMasks(servers, perServer) }

    suspend fun doFindMasks(servers: List<Server>, perServer: Int = 48, enough: Int = 3, group: (Server) -> String = { it.id }, budgetMs: Long = Long.MAX_VALUE, strictServices: Boolean = false): String {
        val deadline = if (budgetMs == Long.MAX_VALUE) Long.MAX_VALUE else android.os.SystemClock.elapsedRealtime() + budgetMs
        if (servers.isEmpty()) return "Выберите серверы"
        val pinned = pinCertificates(servers)
        fun remaining() = if (deadline == Long.MAX_VALUE) 0L else (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        val probe = DpiProxy(app)
        val dpiOk = probe.available(DpiEngine.BYEDPI)
        val tpwsOk = probe.available(DpiEngine.TPWS)
        val cfg = Store.state.value.settings
        val strict = strictServices || cfg.mode == Mode.AUTO
        val net0 = Net.key(app)
        val maskNetwork = Net.underlying(app)?.toString()
        val stats = Store.state.value.maskStats.let { it[net0] ?: it[Net.family(net0)] }.orEmpty()
        // Learned order: masks that passed on this network (on any server) first, untested next, proven failures last.
        fun bucket(m: Mask): Int { val st = stats[m.id] ?: return 1; return if (st.ok > 0) 0 else if (st.fail >= 4) 2 else 1 }
        // «Эволюция масок»: kept auto masks + fresh mutants of what passed here; a few slots per server right after the proven masks.
        val evo = cfg.maskEvolution && (cfg.maskFamilies.isEmpty() || "auto" in cfg.maskFamilies)
        val kept = if (evo) Masks.auto else emptyList()
        val st0 = Store.state.value
        val parents = if (!evo) emptyList() else (stats.entries.filter { it.value.ok > 0 }.sortedByDescending { it.value.score }.mapNotNull { Masks.byId(it.key) } +
            servers.mapNotNull { Masks.byId(st0.state(it).maskFor(net0)) }).filter { it.id != Masks.DEFAULT.id || stats.isEmpty() }.distinctBy { it.id }.take(12)
        val slots = if (evo) (perServer / 5).coerceAtLeast(1) else 0
        val pool = if (evo) MaskLab.breed(parents, (slots * 4).coerceIn(6, 40), known = kept.map { it.id }.toSet()) else emptyList()
        val untried = kept.filter { stats[it.id] == null }
        val evoCand = (0 until maxOf(untried.size, pool.size)).flatMap { i -> listOfNotNull(untried.getOrNull(i), pool.getOrNull(i)) }
        fun maskReady(p:Probe)=p.works && (!strict || AutoLatencyPolicy.accepts(p))
        val order = { s: Server ->
            val ok = { m: Mask -> Masks.compatible(m, s) && (dpiOk || !m.viaByeDpi) }
            val fresh = evoCand.filter(ok).shuffled().take(slots)
            val base = (CloudControl.maskHints(Net.family(net0)).mapNotNull { Masks.byId(it) }.filter(ok) + Masks.searchOrder(dpiOk, s, cfg.maskFamilies, cfg.maskFps) + kept.filter(ok)).distinctBy { it.id }
                .filter { (tpwsOk || !it.dpi.startsWith("TPWS#")) && it !in fresh }
                .sortedWith(compareBy<Mask>({ bucket(it) }, { Masks.cost(it) }, { -(stats[it.id]?.takeIf { s -> s.ok > 0 }?.score ?: 0.0) })).take((perServer - fresh.size).coerceAtLeast(1))
            // proven ones first, then one of every kind of trick (not a dozen variants of the same one)
            val proven = base.filter { bucket(it) == 0 }.take(3).ifEmpty { base.take(1) }
            val rest = Masks.diverse(base - proven.toSet())
            // A few light native variants before new mutants; expensive exploration remains available later.
            val light = rest.filter { Masks.cost(it)<=1 && !it.viaByeDpi && !it.hasNoise }.take(4)
            proven + light + fresh + (rest-light.toSet())
        }
        // Interleaved (1st mask of every server, then the 2nd…): parallel probes hit different servers, and a server
        // that already has [enough] working masks stops early instead of trying all of them.
        val lists = pinned.map { s -> order(s).map { s to it } }
        val variants: List<Pair<Server, Mask?>> = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { r -> lists.mapNotNull { it.getOrNull(r) } }
        val best = HashMap<String, Pair<Mask, Probe>>()
        val good = HashMap<String, MutableList<Pair<Mask, Int>>>()
        val found = HashMap<String, Int>()
        val tried = mutableListOf<Triple<String, String, Boolean>>()
        val done = AtomicInteger()
        // Learning during the run: a kind of trick that failed everywhere while another kind already passes is dropped.
        val kindOk = HashMap<String, Int>(); val kindFail = HashMap<String, Int>()
        val dropAfter = maxOf(6, pinned.size * 2)
        fun dead(m: Mask?): Boolean = m != null && synchronized(best) { val k = Masks.kind(m); (kindOk[k] ?: 0) == 0 && (kindFail[k] ?: 0) >= dropAfter && kindOk.values.any { it > 0 } }
        fun eff(m: Mask, p: Probe) = p.realMs + Masks.cost(m) * 150 + (if (p.kbps > 0) com.vlesscardvpn.model.ServerState.speedPenalty(p.kbps) else 0)
        progress.value = progress.value.copy(title = "Подбор маскировки")
        val phases = variants.indices.groupBy { i -> val m = variants[i].second; when {
            m == null -> 0
            Masks.cost(m) >= 3 -> 3 // tiny fragments / tiny MSS / long pauses do not delay cheap alternatives
            bucket(m) == 0 -> 0 // previously useful, but not assumed fastest
            m.viaByeDpi -> 2
            Masks.isWarp(variants[i].first) || m.hasNoise -> 3
            m.packets.isNotEmpty() || m.signature -> 1
            else -> 0
        } }.toSortedMap()
        for ((phase, indices) in phases) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline || pinned.all { (found[group(it)] ?: 0) >= enough }) break
            val current = indices.map { variants[it] }
            val (own, port) = dpiForTests(current.map { it.second })
            progress.value = progress.value.copy(title = "Подбор обхода: этап ${phase + 1}")
            try {
            // «Жёсткий поиск»: 24 probes at once (PowerPolicy caps it on a hot/low-power phone) —
            // the whole pass takes seconds, not half a minute.
            kotlinx.coroutines.withTimeoutOrNull(if (deadline == Long.MAX_VALUE) Long.MAX_VALUE else remaining()) {
            Tester.real(current, cfg.testUrl, port, own.ports, youtube = false, telegram=strict, requireTelegram=strict, batch = 64, parallel = 24, attempts = 1, budgetMs = remaining(),
                skip = { i -> android.os.SystemClock.elapsedRealtime() >= deadline || synchronized(best) { (found[group(variants[indices[i]].first)] ?: 0) >= enough } || dead(variants[indices[i]].second) }) { i, p ->
                if (Net.key(app) != net0 || Net.underlying(app)?.toString() != maskNetwork) return@real
                val (s, m) = variants[indices[i]]
                synchronized(best) {
                    val cur = best[s.id]
                    if (m != null && p !== Tester.SKIPPED && (!strict || !p.works || p.tgOk!=null)) (if (maskReady(p)) kindOk else kindFail).merge(Masks.kind(m), 1, Int::plus)
                    if (maskReady(p) && m != null && (cur == null || eff(m, p) < eff(cur.first, cur.second))) best[s.id] = m to p
                    if (maskReady(p) && m != null) { good.getOrPut(s.id) { mutableListOf() } += m to p.realMs; found.merge(group(s), 1, Int::plus) }
                    if (m != null && p !== Tester.SKIPPED && (!strict || !p.works || p.tgOk!=null)) tried += Triple(s.id, m.id, maskReady(p))
                }
                step(done.incrementAndGet(), variants.size)
            }
            }
            } finally { own.close() }
        }
        // Double check: a mask that passed once can be a fluke (the TSPU sometimes lets the first connection through).
        // The 2 fastest of every server are probed again, now with YouTube; the one that passes again wins.
        val confirm = pinned.flatMap { s -> good[s.id].orEmpty().let { g -> (g.sortedBy { it.second }.take(2) + g.sortedWith(compareBy({ Masks.cost(it.first) }, { it.second })).take(1)) }
            .distinctBy { it.first.id }.map { s to it.first } }
        val confirmedServers = HashSet<String>()
        if (MaskSamplePolicy.needsConfirmation(confirm.size,pinned.size,strict) && android.os.SystemClock.elapsedRealtime() < deadline) {
            val res = java.util.concurrent.ConcurrentHashMap<Int, Probe>()
            progress.value = progress.value.copy(title = "Двойная проверка маскировок")
            val (own2, port2) = dpiForTests(confirm.map { it.second })
            try {
                kotlinx.coroutines.withTimeoutOrNull(if (deadline == Long.MAX_VALUE) Long.MAX_VALUE else remaining()) {
                Tester.real(confirm, cfg.testUrl, port2, own2.ports, youtube = true, telegram = strict, requireTelegram = strict, batch = 64, parallel = 24, attempts = 1, big = true, budgetMs = remaining()) { i, p -> res[i] = p; step(i + 1, confirm.size) }
                }
            } finally { own2.close() }
            pinned.forEach { s ->
                val mine = confirm.indices.filter { confirm[it].first.id == s.id }
                // passed again (with YouTube) and the fastest in real use: ping + download speed + how heavy the mask is
                val pick = mine.filter { res[it]?.works == true && res[it]?.ytOk == true && (!strict && res[it]?.tgOk!=false || res[it]?.let(AutoLatencyPolicy::accepts)==true) }.minByOrNull { eff(confirm[it].second, res.getValue(it)) }
                    ?: mine.filter { res[it]?.let { p -> p.works && p.ytOk != false && (!strict && p.tgOk!=false || AutoLatencyPolicy.accepts(p)) } == true }.minByOrNull { eff(confirm[it].second, res.getValue(it)) }
                if (pick != null) { best[s.id] = confirm[pick].second to res.getValue(pick); confirmedServers += s.id }
                else if (mine.any { res[it] != null && res[it] !== Tester.SKIPPED }) best.remove(s.id)
                mine.forEach { i -> if (res[i]?.works == false) synchronized(best) { tried += Triple(s.id, confirm[i].second.id, false) } }
            }
        }
        if (Net.key(app) != net0 || Net.underlying(app)?.toString() != maskNetwork) return "Сеть изменилась — результаты старой маскировки отброшены"
        // A strict search may not adopt first-pass evidence when confirmation timed out or was skipped.
        if (strict) best.keys.retainAll(confirmedServers)
        val net = net0
        // Passed mutants join the auto masks (before any server points at them); failed fresh ones are forgotten.
        val keptIds = kept.map { it.id }.toSet()
        val winners = tried.filter { it.third && it.second.startsWith(MaskLab.PREFIX) }.map { it.second }.distinct()
            .mapNotNull { id -> Masks.byId(id) ?: pool.firstOrNull { it.id == id } }
        Store.update { st -> st.copy(settings = st.settings.copy(autoMasks = MaskLab.keep(st.settings.autoMasks, winners, st.maskStats))) }
        val winIds = winners.map { it.id }.toSet()
        // Failures of a server where nothing worked say nothing about the masks (the server may be dead).
        Store.recordMasks(net, tried.filter { (sid, mid, ok) -> (ok || good.containsKey(sid)) && (!mid.startsWith(MaskLab.PREFIX) || mid in keptIds || mid in winIds) }
            .map { it.second to it.third })
        var adoptedMasks=0
        pinned.forEach { s ->
            val b = best[s.id]
            // Search failure proves no successful replacement, not failure of the current working route.
            if(b==null) return@forEach
            val initial=st0.state(s)
            Store.setState(s.id) {current->
                if(Net.key(app)!=net0 || Net.underlying(app)?.toString()!=maskNetwork ||
                    !MaskSamplePolicy.unchanged(current,initial,net)) current
                else {
                    adoptedMasks++
                    MaskSamplePolicy.adopt(current,net,b.first.id,System.currentTimeMillis(),b.second,
                        telegramSampled=b.second.tgOk!=null,youtubeSampled=b.second.ytOk!=null,trustedSpeed=false)
                        .copy(goodMasks=good[s.id].orEmpty().sortedBy{it.second}.map{it.first.id}.filter{it!=b.first.id}.take(8))
                }
            }
        }
        val bred = if (evo) ", новых авто-масок: ${winIds.count { it !in keptIds }}" else ""
        return "Подтверждено маскировок для ${best.size} из ${pinned.size} серверов; применено $adoptedMasks, более свежие результаты сохранены (перебрано ${variants.size} вариантов$bred, сеть «$net»)"
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
                s.id to MaskSamplePolicy.invalidate(ss).copy(maskId = next, netMasks = ss.netMasks + (net to next), goodMasks = ss.goodMasks.drop(1) + ss.maskFor(net))
            })
        }
        return changed
    }
    /** «Кости»: instead of a proven mask — a random compatible one for this server (a lucky try; the proven ones stay in goodMasks). */
    fun rollDice(servers: List<Server>): Int {
        val net = Net.key(app)
        var rolled = 0
        Store.update { st ->
            st.copy(states = st.states + servers.mapNotNull { s ->
                val ss = st.state(s)
                val pool = com.vlesscardvpn.xray.Masks.searchOrder(true, s).filter { it.id != ss.maskId }
                if (pool.isEmpty()) return@mapNotNull null
                rolled++
                val dice = pool.random()
                s.id to MaskSamplePolicy.invalidate(ss).copy(maskId = dice.id, netMasks = ss.netMasks + (net to dice.id),
                    goodMasks = if (ss.maskId in ss.goodMasks) ss.goodMasks else ss.goodMasks + ss.maskId)
            })
        }
        return rolled
    }

    /** Collects per-server results from parallel callbacks and applies them to the store in chunks. */
    class StateBatch(private val size: Int = 200, private val maxAgeMs: Long = 700) {
        private val pending = java.util.concurrent.ConcurrentHashMap<String, (com.vlesscardvpn.model.ServerState) -> com.vlesscardvpn.model.ServerState>()
        @Volatile private var lastFlush = System.currentTimeMillis()
        /** Flushes by size or by time: the list updates smoothly instead of once per result. */
        fun put(id: String, f: (com.vlesscardvpn.model.ServerState) -> com.vlesscardvpn.model.ServerState) {
            pending[id] = f
            if (pending.size >= size || System.currentTimeMillis() - lastFlush > maxAgeMs) flush()
        }
        @Synchronized fun flush() {
            lastFlush = System.currentTimeMillis()
            val snap = HashMap(pending); snap.keys.forEach { pending.remove(it) }
            if (snap.isEmpty()) return
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
        runCatching { Report.prime(app); CloudSpeed.loadCached(app) }
        refreshPublicCloud()
        val st = Store.state.value
        if (!PhonePower.refresh(app).allowLight || !st.settings.autoOptimize || job?.isActive == true || Tunnel.status.value.state != Tunnel.State.IDLE) return
        if (st.servers.any { st.state(it).works }) return
        launch("Фоновая подготовка") {
            if (Store.state.value.servers.isEmpty()) doRefresh(firstReady = { list -> quickTest(list) })
            else quickTest(Store.state.value.servers)
            "Короткая подготовка завершена. Полный каталог и скорость проверяются после подключения"
        }
    }

    /**
     * While connected, every ~20 min (TunnelService): re-tests the selected servers and the best other ones outside
     * the tunnel. If a selected server died or others are clearly faster, switches to the fastest and reconnects;
     * if nothing works, searches new masks. Returns true when the service should reconnect.
     */
    private suspend fun realSpeed(servers: List<Server>) {
        if (servers.isEmpty()) return
        val net = Net.key(app); val network = Net.underlying(app)?.toString(); val st = Store.state.value
        val variants = pinCertificates(servers).map { it to Masks.byId(st.state(it).maskFor(net)) }
        val speedStartedAt=System.currentTimeMillis()
        val (own, port) = dpiForTests(variants.map { it.second })
        try {
            val r = Tester.speedMany(variants, port, own.ports)
            if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return
            Store.setStates(variants.indices.filter { r[it] > 0 }.associate { i -> variants[i].first.id to { x: com.vlesscardvpn.model.ServerState ->
                if (Net.key(app)!=net || Net.underlying(app)?.toString()!=network || x.speedCheckedAt>speedStartedAt || x.maskFor(net) != st.state(variants[i].first).maskFor(net)) x
                else x.copy(kbps = r[i],speedNetwork=net,speedMask=x.maskFor(net),speedCheckedAt=System.currentTimeMillis())
            } })
        } finally { own.close() }
    }

    /** «Эта сеть → эти серверы»: what worked here last time is picked again at once when the phone comes back to it. */
    fun rememberNet(net: String) {
        val ids = Store.state.value.selected.map { it.id }
        if (ids.isNotEmpty()) Store.update { it.copy(settings = it.settings.copy(netServers = (it.settings.netServers - net + (net to ids)).entries.toList().takeLast(12).associate { e -> e.key to e.value })) }
    }

    /** Returns true when the remembered servers of [net] were selected. */
    fun recallNet(net: String): Boolean {
        val st = Store.state.value
        val ids = (st.settings.netServers[net] ?: return false).toSet()
        val ok = st.servers.filter { it.id in ids && st.state(it).works }
        if (ok.isEmpty() || ok.map { it.id }.toSet() == st.selected.map { it.id }.toSet()) return false
        selectBest(ok.size, ok); return true
    }

    /** Connected after the quick check (or on old results): the first background pass tests the rest and re-ranks. */
    @Volatile var deepPending = false

    /**
     * Selected servers that pass only with a heavy mask (tiny packets / one stream — YouTube crawls): try the light
     * masks again on this network, with a download-speed test; a light one that passes wins.
     */
    private suspend fun lightenMasks(wifi: Boolean) {
        val st=Store.state.value;val net=Net.key(app);val handle=Net.underlying(app)?.toString()
        if(st.settings.mode!=Mode.AUTO || SessionHold.active()) return
        val heavy=st.selected.filter {s->Masks.byId(st.state(s).maskFor(net))?.let {Masks.cost(it)>=2}==true}.take(if(wifi) 2 else 1)
        for(server in heavy) {
            if(SessionHold.active() || GameSession.active() || !PhonePower.refresh(app).let{it.allowLight && !it.deferTraffic}) return
            if(Net.key(app)!=net || Net.underlying(app)?.toString()!=handle) return
            kotlinx.coroutines.withTimeoutOrNull(6500L) {tuneAutoMask(server,net,handle)}
        }
    }

    suspend fun decideYoutube(): Boolean = false

    fun ytDpiWins(serverKbps: Int, dpiKbps: Int) = dpiKbps > 0 && dpiKbps > serverKbps * 1.3 + 500

    fun boostYoutube(maintenanceReconnect: (() -> Unit)? = null) = launch("Ускоряю YouTube через сервер") {
        // Measure before changing selection; never disable QUIC policy or masks speculatively.
        val net=Net.key(app); val handle=Net.underlying(app)?.toString()
        fun sameNetwork()=Net.key(app)==net && Net.underlying(app)?.toString()==handle
        val measuredAfter=System.currentTimeMillis()
        val wasConnected=Tunnel.socks!=null
        val initial = Store.state.value
        val fresh = runCatching {
            CloudSpeed.refresh(app)
            CloudSpeed.pick(initial.servers.filter { !initial.state(it).works && AutoEligibility.vlessServer(it) }, emptyList(), n = 6)
        }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) realTest(fresh, "Проверяю быстрые VLESS-серверы", big = false, youtube = true, telegram = false, attempts = 1)
        val state = Store.state.value
        if(!sameNetwork()) return@launch "Сеть изменилась — выбор маршрута оставлен без изменений."
        val top = (state.selected.filter(AutoEligibility::vlessServer).take(1) + state.servers.filter { state.state(it).works && AutoEligibility.vlessServer(it) }
            .sortedWith(ServerChoice.downloadOrder({ state.state(it) }, Net.key(app), System.currentTimeMillis())).take(6) +
            fresh.filter { state.state(it).works }).distinctBy { it.id }
        if (top.isEmpty()) return@launch "Рабочий VLESS-сервер не найден — прямое подключение не включено. Проверьте серверы и повторите."
        progress.value = progress.value.copy(title = "Сравниваю YouTube и Telegram через VLESS")
        realTest(top, "Сравниваю YouTube и Telegram через VLESS", big = false, youtube = true, telegram = true, attempts = 1, parallel = 4)
        realSpeed(top)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(!sameNetwork()) return@launch "Сеть изменилась — измерения не применены. Текущий маршрут сохранён."
        val updated=Store.state.value
        fun freshSpeed(s:Server):Int = updated.state(s).let { x ->
            if(x.works && x.speedNetwork==net && x.speedMask==x.maskFor(net) && x.speedCheckedAt>=measuredAfter) x.kbps else 0
        }
        val eligible=AutoEligibility.servicePool(top.filter {freshSpeed(it)>0}, {updated.state(it)}, net,System.currentTimeMillis())
        val winner=eligible.maxByOrNull(::freshSpeed)
            ?: return@launch "Ускорение не подтверждено измерениями — маршрут, маски и настройки сохранены."
        val primary=updated.selected.firstOrNull()
        val before=primary?.let(::freshSpeed) ?: 0
        if(!BoostDecision.shouldReplace(wasConnected,primary?.id,winner.id,before,freshSpeed(winner)))
            return@launch "Заметного ускорения не подтверждено — оставляю текущий маршрут без переподключения."
        if(!sameNetwork()) return@launch "Сеть изменилась — выбор не применён."
        selectBest(1, close=false, among=listOf(winner))
        if(Store.state.value.selected.none {it.id==winner.id}) return@launch "Кандидат не прошёл отбор — переподключение не выполнялось."
        val connected=Tunnel.socks!=null
        if(connected) withContext(Dispatchers.Main) {
            if(sameNetwork()) maintenanceReconnect?.invoke() ?: com.vlesscardvpn.vpn.TunnelService.start(app)
        }
        val speed="%.1f".format(java.util.Locale.US,freshSpeed(winner)/1000.0)
        "Выбран измеренный маршрут: $speed Мбит/с. Маски и политика QUIC сохранены." +
            if(connected) " Применяю проверенный выбор." else " Подключитесь, чтобы применить выбор."
    }

    /** «Облегчи маску» (Помощник). */
    fun lighten(maintenanceReconnect: (() -> Unit)? = null) = launch("Облегчаю маски") {
        val before = Store.state.value.let { s -> s.selected.count { Masks.byId(s.state(it).maskFor(Net.key(app)))?.let { m -> Masks.cost(m) >= 2 } == true } }
        if (before == 0) return@launch "Тяжёлых масок у выбранных серверов нет — уже самые быстрые."
        lightenMasks(true)
        val after = Store.state.value.let { s -> s.selected.count { Masks.byId(s.state(it).maskFor(Net.key(app)))?.let { m -> Masks.cost(m) >= 2 } == true } }
        if (after < before && Tunnel.socks != null) withContext(Dispatchers.Main) { maintenanceReconnect?.invoke() ?: com.vlesscardvpn.vpn.TunnelService.start(app) }
        "Облегчено масок: ${before - after} из $before" + if (after < before && Tunnel.socks != null) (if (maintenanceReconnect != null) " — применю в паузе трафика." else " — переподключаюсь.") else if (after == before) " (лёгкие маски здесь не прошли — тяжёлые оставлены, иначе не будет работать)." else "."
    }

    val reserveStatus = kotlinx.coroutines.flow.MutableStateFlow("Резервы: фоновая проверка ещё не запускалась")
    private var catalogAt = -300000L
    private suspend fun catalogWave() {
        val now = android.os.SystemClock.elapsedRealtime()
        val plan = PhonePower.refresh(app)
        if (!plan.allowLight) { reserveStatus.value = "Резервы отложены: ${plan.reason}"; return }
        if (progress.value.running) { reserveStatus.value = "Резервы ждут завершения проверки по нажатию"; return }
        if (now - catalogAt < 30000) return
        catalogAt = now
        val st = Store.state.value
        val available = preferred(st,st.servers.filter { !st.state(it).selected })
        val affinity = SourceAffinity.order(available,st.servers,{st.state(it)},Net.key(app),System.currentTimeMillis()) { CloudControl.sources(it) }
        val cloud = (CloudSpeed.pick(available,emptyList(),n=4) + affinity.take(4) + CloudControl.ranked(available,8)).distinctBy { it.id }.take(16)
        val wave = PublicCloudQueue.merge(available.sortedBy { st.state(it).checkedAt },cloud,available,ConnectPolicy.BACKGROUND,slots=8)
        reserveStatus.value = "Фон: TCP-волна ${wave.size} из ${available.size} резервных кандидатов"
        val states = StateBatch(); val alive = java.util.concurrent.ConcurrentHashMap<String, Int>()
        Tester.tcp(wave, parallel = 32, timeoutMs = 1500) { s, ms -> alive[s.id] = ms; states.put(s.id) { it.copy(tcpMs = ms, checkedAt = System.currentTimeMillis()) } }
        states.flush()
        if (!PhonePower.refresh(app).allowLight) return
        val reachable = wave.filter { (alive[it.id] ?: 0) > 0 }
        val test = PublicCloudQueue.merge(reachable.sortedBy { alive[it.id] },cloud,reachable,ConnectPolicy.BACKGROUND,slots=8)
        var checked = 0
        val prev = progress.value
        try {
            val net=Net.key(app);val handle=Net.underlying(app)?.toString()
            fun valid()=Net.key(app)==net && Net.underlying(app)?.toString()==handle
            val checkedAfter = System.currentTimeMillis()
            realTest(test,"Фон: Telegram и YouTube, волна ${test.size} из ${available.size}",big=false,youtube=true,
                attempts=1,parallel=4,budgetMs=ConnectPolicy.QUICK_BUDGET_MS)
            checked=Store.state.value.let { snapshot -> test.count { snapshot.state(it).checkedAt >= checkedAfter } }
            reserveStatus.value = if (valid()) "Резервы: HTTPS-проверок $checked, рабочих ${Store.state.value.let { snapshot -> test.count { snapshot.state(it).works && snapshot.state(it).checkedAt >= checkedAfter } }} · результаты сервисов отдельно"
                else "Резервы: сеть изменилась, старые результаты не используются"
            if (valid()) AutoSelection.compare(test,{Store.state.value.state(it)},net,
                now={System.currentTimeMillis()},elapsed={android.os.SystemClock.elapsedRealtime()},validNetwork={valid()},
                allowDownload={PhonePower.refresh(app).let { it.allowed && !it.deferTraffic }},gaming=GameSession.active(),
                hint={maxOf(CloudControl.speed(it),CloudSpeed.speedOf(Report.serverKey(it)))},
                onInconclusive={s,at->expireUnreproducedSpeed(s,net,at)}) { server,budget ->
                realTest(listOf(server),"Фон: сравниваю скорость",big=true,youtube=false,attempts=1,parallel=1,budgetMs=budget)
            }
        } finally { progress.value = prev }
        AppWatch.note("Фон: просмотрены ${wave.size} запасных маршрутов, через Xray проверены $checked; основной туннель не ждёт этот поиск")
    }

    private var serviceCursor = 0; private var serviceAt = -300000L
    /** Small endpoint checks also on mobile; each round is bounded and does not need Usage Access. */
    suspend fun checkServiceRoutes(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - serviceAt < (if (serviceCursor < ServiceRoutes.targets.size) 30000L else 300000L) || progress.value.running || !PhonePower.refresh(app).let { it.allowLight && !it.deferTraffic }) return false
        val st = Store.state.value; val selected = st.selected
        if (selected.isEmpty()) return false
        serviceAt = now
        val target = ServiceRoutes.targets[serviceCursor++ % ServiceRoutes.targets.size]
        if (target.id == "calls" && (st.settings.torAll || st.settings.torRouting && st.settings.services.any { it in setOf("telegram", "whatsapp", "discord") })) return false
        if (target.id != "calls" && ServiceRoutes.native(target) && st.settings.services.isNotEmpty() && target.id !in st.settings.services) return false
        val eligible = selected.filter { !target.ai || Countries.of(it.name) !in AppWatch.AI_BAD }.take(4)
        if (eligible.isEmpty()) return false
        val net = Net.key(app); val handle = Net.underlying(app)?.toString()
        val results = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val variants = eligible.map { it to Masks.byId(st.state(it).maskFor(net)) }
        val (own, port) = dpiForTests(variants.map { it.second })
        try {
            Tester.real(variants, if (ServiceRoutes.native(target)) st.settings.testUrl else target.url,
                port, own.ports, parallel = 2, attempts = 1, big = false, youtube = target.id == "youtube",
                telegram = target.id == "telegram", calls = target.id == "calls",
                budgetMs = if (ServiceRoutes.native(target)) 6000 else ConnectPolicy.QUICK_BUDGET_MS) { i, p ->
                if (Net.key(app) == net && Net.underlying(app)?.toString() == handle)
                    ServiceRoutes.measurement(target, p)?.let { results[eligible[i].id] = it }
            }
        } finally { own?.close() }
        if (Net.key(app) != net || Net.underlying(app)?.toString() != handle || results.isEmpty()) return false
        val current = Store.state.value
        if (eligible.any { current.state(it).maskFor(net) != st.state(it).maskFor(net) } ||
            current.selected.map { it.id }.toSet() != selected.map { it.id }.toSet()) return false
        val changed = ServiceRoutes.update(net, target.id, results.entries.map { it.key to it.value }, now)
        // Tor in Auto: a service that no VLESS route can reach falls back to the local Tor
        // SOCKS (Tor over the tunnel) once, instead of silently staying broken.
        val st2 = Store.state.value
        if (st2.settings.torAuto && !changed && st2.settings.services.contains(target.id) &&
            results.values.none { it > 0 } && !st2.settings.torRouting)
            Store.update { it.copy(settings = it.settings.copy(torRouting = true)) }
        AppWatch.note("${target.title}: ${if (ServiceRoutes.native(target)) "протокол/медиаданные" else "HTTPS"} доступны через ${results.values.count { it > 0 }} из ${results.size} проверенных маршрутов; это не проверка аккаунта")
        return changed && selected.size > 1
    }

    private fun automaticOrder(st: AppState, servers: List<Server>): List<Server> {
        val net = Net.key(app); val now = System.currentTimeMillis()
        return if (st.settings.mode == Mode.AUTO && !GameSession.active())
            servers.sortedWith(ServerChoice.downloadOrder({ st.state(it) },net,now))
        else servers.sortedBy { st.state(it).score }
    }

    /** Whether the last [optimize] pass switched servers (TunnelService checks again sooner then). */
    @Volatile var lastOptimizeSwitched = false

    suspend fun optimize(): Boolean {
        lastOptimizeSwitched = false
        val lightPlan = PhonePower.refresh(app)
        if (!lightPlan.allowLight) {
            if (com.vlesscardvpn.BuildConfig.DEBUG) android.util.Log.i("E2E", "optimize skipped=power")
            return false
        }
        var st = Store.state.value
        if ((!st.settings.autoOptimize && st.settings.mode != Mode.AUTO) || job?.isActive == true) {
            if (com.vlesscardvpn.BuildConfig.DEBUG) android.util.Log.i("E2E", "optimize skipped=${if(job?.isActive == true) "foreground_busy" else "disabled"}")
            return false
        }
        val originalNet = Net.key(app)
        val originalSelection = st.selected.map { AutoRoutePolicy.configuration(it,st.state(it),originalNet) }.toSet()
        if (st.settings.mode == com.vlesscardvpn.model.Mode.BYEDPI || st.settings.mode == com.vlesscardvpn.model.Mode.HYBRID) return false
        val wifi = unmetered()
        catalogWave()
        st = Store.state.value
        var ytSwitch = false
        if (deepPending && PhonePower.refresh(app).allowed) {
            // connected right after the quick check: now test the rest calmly (outside the tunnel) and lighten heavy masks
            deepPending = false
            val prev0 = progress.value
            val s0 = Store.state.value
            val rest = s0.servers.filter { s0.state(it).tcpMs > 0 && (s0.state(it).realMs < 0 || System.currentTimeMillis() - s0.state(it).checkedAt > 6 * 3600_000L) }
                .sortedBy { s0.state(it).tcpMs }.take(if (wifi) 12 else 6)
            if (rest.isNotEmpty()) realTest(rest, "Фоновая проверка", big = true, attempts = 1)
            if (!PhonePower.refresh(app).allowed) { deepPending = true; progress.value = prev0; return false }
            lightenMasks(wifi)
            // Wi-Fi: a real 3-second download through the 5 best (≈ what YouTube gets), not a 256 KB guess
            if (wifi) Store.state.value.let { s1 -> realSpeed(automaticOrder(s1, s1.servers.filter { s1.state(it).works }).take(5)) }
            progress.value = prev0
        }
        if (!PhonePower.refresh(app).allowLight) return ytSwitch
        st = Store.state.value
        // An empty Auto selection is recovered from the best working pool.
        val sel = st.selected.ifEmpty { automaticOrder(st, st.servers.filter { st.state(it).works }).take(AutoEligibility.TOP) }
        val others = automaticOrder(st, st.servers.filter { it !in sel && st.state(it).works }).take(if (wifi) 12 else 6)
        val cand = (sel + others).distinctBy { it.id }
        if (cand.isEmpty()) return ytSwitch
        val prev = progress.value
        val sampleNet = Net.key(app); val sampleHandle = Net.underlying(app)?.toString()
        fun sameNetwork() = Net.key(app) == sampleNet && Net.underlying(app)?.toString() == sampleHandle
        try {
            val throughputMode = st.settings.mode == Mode.AUTO && !GameSession.active()
            // Concurrent downloads share the line and cannot rank throughput. First check HTTPS only.
            realTest(cand, "Фоновая проверка", big = wifi && !throughputMode && PhonePower.refresh(app).allowed, youtube = throughputMode,
                attempts = 1, parallel = 4, budgetMs = ConnectPolicy.QUICK_BUDGET_MS)
            if (!sameNetwork()) return false
            if (throughputMode) AutoSelection.compare(cand, { Store.state.value.state(it) }, sampleNet,
                now = { System.currentTimeMillis() }, elapsed = { android.os.SystemClock.elapsedRealtime() },
                validNetwork = { sameNetwork() }, gaming = false,
                allowDownload = { PhonePower.refresh(app).let { it.allowed && it.allowLight && !it.deferTraffic } },
                hint = { maxOf(CloudControl.speed(it),CloudSpeed.speedOf(Report.serverKey(it))) },
                onInconclusive = { server, at -> expireUnreproducedSpeed(server,sampleNet,at) }) { server, budget ->
                realTest(listOf(server), "Фон: сравниваю скорость по очереди", big = true, youtube = false,
                    attempts = 1, parallel = 1, budgetMs = budget)
            }
        } finally { progress.value = prev }
        if (!sameNetwork()) return false
        val now = Store.state.value
        val selOk = sel.filter { now.state(it).works }
        val best = automaticOrder(now, cand.filter { now.state(it).works })
        if (best.isEmpty()) {
            val alive = now.servers.filter { now.state(it).tcpMs > 0 }.sortedBy { now.state(it).tcpMs }.take(8)
            if (alive.isNotEmpty()) doFindMasks(alive, 24)
            if (!sameNetwork()) return false
            val after = Store.state.value
            return if (alive.any { after.state(it).works }) { selectBest(10, close = true, among = alive.filter { after.state(it).works }); true } else ytSwitch
        }
        val median = selOk.map { now.state(it).score }.sorted().let { if (it.isEmpty()) Int.MAX_VALUE else it[it.size / 2] }
        val bestMs = now.state(best.first()).score
        // Ping is not everything: a server that answers fast but breaks big downloads (256 KB) feels slow — video stalls.
        val selBroken = wifi && selOk.isNotEmpty() && selOk.all { now.state(it).bigOk == false } && best.any { now.state(it).bigOk == true }
        val speedMode = now.settings.mode == Mode.AUTO && !GameSession.active()
        val net = Net.key(app); val stamp = System.currentTimeMillis()
        val currentFastest = selOk.minWithOrNull(ServerChoice.downloadOrder({ now.state(it) },net,stamp))
        val fasterDownload = ServerChoice.downloadUpgrade(currentFastest?.let { now.state(it) },now.state(best.first()),net,stamp)
        val qualityGain = if (speedMode) fasterDownload else median > bestMs * 1.6 && median - bestMs > 150
        val repairRequired = selOk.size < sel.size || selOk.size < (if (ServerChoice.automaticPrimary(st.settings.mode == Mode.AUTO,st.settings.balance == com.vlesscardvpn.model.Balance.LEAST_PING,GameSession.active())) 1 else minOf(3,best.size)) || selBroken
        val switch = BackgroundOptimizationPolicy.mayReplace(repairRequired,qualityGain)
        val pick = if (selBroken) best.filter { now.state(it).bigOk == true } else best
        if (switch) selectBest(AutoEligibility.TOP, close = true, among = pick)
        val applied = Store.state.value
        val changed = originalSelection != applied.selected.map { AutoRoutePolicy.configuration(it,applied.state(it),originalNet) }.toSet()
        lastOptimizeSwitched = changed
        android.util.Log.i("E2E", "optimize sel=${sel.size} ok=${selOk.size} median=$median best=$bestMs switch=$switch changed=$changed")
        return ytSwitch || changed
    }

    // ---------- WARP ----------
    /**
     * «WARP»: registers up to 3 free Cloudflare accounts (one key each — the tester runs one WireGuard tunnel per key
     * at a time), 4 endpoints per account, then searches UDP noise masks for them and selects the working ones.
     */
    fun setupWarp(accounts: Int = 1, onlyKey: String? = null) = launch("WARP") {
        val msg = doSetupWarp(if (onlyKey != null) 1 else accounts, onlyKey)
        // Ready = connect right away (or reconnect onto WARP): no guessing whether WARP is on.
        val st = Store.state.value
        val warpOk = msg.startsWith("WARP готов:") && st.selected.any { Masks.isWarp(it) && st.state(it).works }
        if (warpOk && (android.net.VpnService.prepare(app) == null)) {
            withContext(Dispatchers.Main) { com.vlesscardvpn.vpn.TunnelService.start(app) }
            msg.replace("Нажмите «Подключить».", "Подключаюсь через проверенную точку WARP; статус WARP проверяется после запуска.")
        } else msg
    }

    /** Remembers Cloudflare's answers about WARP+ keys («Ключи WARP+» shows them; used-up keys go last next time). */
    private fun rememberKeys(results: Map<String, String>) {
        if (results.isNotEmpty()) Store.update { st -> st.copy(settings = st.settings.copy(warpKeyStatus = st.settings.warpKeyStatus + results)) }
    }

    /** [onlyKey]: «Подключить с этим ключом» in «Ключи WARP+» — one account, just this key. */
    suspend fun doSetupWarp(accounts: Int = 1, onlyKey: String? = null): String =
        kotlinx.coroutines.withTimeoutOrNull(120_000L) { setupWarpBounded(accounts, onlyKey) }
            ?: "WARP: лимит ожидания исчерпан. Сохранённые серверы и выбранный маршрут не удалены."

    private suspend fun setupWarpBounded(accounts: Int, onlyKey: String?): String {
        val setupNet = Net.key(app) to Net.underlying(app)?.toString()
        val setupStarted = System.currentTimeMillis()
        val count = accounts.coerceIn(1,3)
        progress.value = progress.value.copy(title = "WARP: регистрация")
        val accs = mutableListOf<Warp.Account>(); var err = ""
        var dpi: DpiSet? = null
        var plusErr = ""
        try {
        withContext(Dispatchers.IO) {
            repeat(count) { i ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                step(i, count)
                var r = runCatching { kotlinx.coroutines.withTimeout(12_000L) { Warp.register() } }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (r.isFailure && dpi == null) {
                    // Cloudflare API blocked: retry through the DPI bypass picked for this network.
                    dpi = runCatching { DpiSet(app).start(Store.state.value.settings, Net.key(app), true, emptySet(), com.vlesscardvpn.BuildConfig.DEBUG) }.getOrNull()
                    dpi?.currentPort?.let { Warp.extraSocks = listOf(it); r = runCatching { kotlinx.coroutines.withTimeout(12_000L) { Warp.register() } } }
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                r.onSuccess { accs += it }.onFailure { err = Errors.human(it) }
            }
        }
        // Respect configured keys, but cap attempts and time so WARP+ cannot stall ordinary WARP.
        val cfg = Store.state.value.settings
        val own = WarpKeys.parse(cfg.warpKeys)
        val order = if (onlyKey != null) mutableListOf(onlyKey)
            else WarpKeys.order(own, cfg.warpBuiltinKeys, cfg.warpKeyOff, cfg.warpKeyStatus).toMutableList()
        if (order.isNotEmpty()) {
            progress.value = progress.value.copy(title = "WARP: ключ WARP+")
            val seen = HashMap<String, String>()
            withContext(Dispatchers.IO) {
                accs.indices.forEach { i ->
                    val upgraded = kotlinx.coroutines.withTimeoutOrNull(12_000L) { Warp.upgrade(accs[i], order, maxTries = if (onlyKey != null) 1 else 2) { k, err ->
                        WarpKeys.classify(err)?.let { seen[k] = WarpKeys.mark(it) }
                        // used up / invalid: don't retry it for the next account
                        if (err != null && WarpKeys.classify(err) != null) order.remove(k)
                    }
                    }
                    val (key, e) = upgraded ?: (null to "Лимит ожидания WARP+")
                    // a key that worked is the best bet for the next account too
                    if (key != null) { accs[i] = accs[i].copy(plus = true); order.remove(key); order.add(0, key) } else plusErr = e
                }
            }
            rememberKeys(seen)
        }
        } finally { dpi?.close(); Warp.extraSocks = emptyList() }
        val plus = accs.count { it.plus }
        android.util.Log.i("E2E", "warp accounts=${accs.size} plus=$plus err=$err plusErr=$plusErr")
        if (accs.isEmpty()) return "WARP: регистрация не удалась ($err). Попробуйте после подключения к любому серверу или ByeDPI."
        val list = accs.flatMapIndexed { i, a -> Warp.servers(a, Warp.endpointsFor(i)) }
        // Preserve existing accounts, selected route and results when a new setup fails.
        Store.addServers(list)
        // one working endpoint per account is enough (only one tunnel per key can run at a time anyway)
        // 10 compatible candidates, 90 seconds at most: an account whose endpoints are all dead must not stall the search
        val res = doFindMasks(list, perServer = 10, enough = 1, group = { it.secret }, budgetMs = 90_000, strictServices = true)
        val st = Store.state.value
        val ok = list.filter { st.state(it).works }
        android.util.Log.i("E2E", "warp servers=${list.size} working=${ok.size} masks=${ok.map { st.state(it).maskId }}")
        // one endpoint per account: two tunnels with the same key at once would roam and stall
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        var chosen = false
        Store.update { current ->
            if ((Net.key(app) to Net.underlying(app)?.toString()) != setupNet) current
            else WarpSetupPolicy.select(current,list,setupNet.first,setupStarted).also { chosen = it !== current }
        }
        return if (!chosen) "WARP: новый маршрут не подтверждён ($res). Сохранённые серверы не удалены. Причиной могут быть сеть, API или недоступность точек входа; блокировка UDP не доказана."
        else "WARP готов: рабочих точек входа ${ok.size} (по одной на аккаунт, всего ${list.size}), выбран один основной маршрут в режиме «Серверы». " +
            (if (plus > 0) "WARP+: $plus из ${accs.size} аккаунтов. " else if (plusErr.isNotEmpty()) "WARP+ не подключился ($plusErr) — обычный WARP. " else "") +
            "Нажмите «Подключить»."
    }

    // ---------- Auto mode ----------
    /**
     * «Авто»: cached candidates and subscription rescue race, then bounded reserve/mask discovery.
     * Only a data-proven remote route may call [onReady]; no standalone/direct fallback.
     */
    private val autoGeneration = AtomicInteger()
    fun autoConnect(onReady: () -> Unit) {
        val ticket = autoGeneration.incrementAndGet()
        val expectedNetwork = java.util.concurrent.atomic.AtomicReference(Net.key(app) to Net.underlying(app)?.toString())
        val ready = {
            if (autoGeneration.get() == ticket) {
                if (expectedNetwork.get() == (Net.key(app) to Net.underlying(app)?.toString())) onReady()
                else {
                    if (com.vlesscardvpn.BuildConfig.DEBUG) android.util.Log.i("E2E", "auto ready_blocked=network_changed")
                    selectionNetworkChanged(); Unit
                }
            } else if (com.vlesscardvpn.BuildConfig.DEBUG) android.util.Log.i("E2E", "auto ready_blocked=cancelled")
        }
        runCatching { Report.prime(app); CloudSpeed.loadCached(app) }
        refreshPublicCloud(foreground = true)
        val st = Store.state.value
        val working = AutoEligibility.servicePool(preferred(st,st.servers.filter { st.state(it).works }),
            { st.state(it) }, Net.key(app), System.currentTimeMillis())
        val busy = job
        // Instant start: something worked before → connect now; the tunnel itself re-checks, heals and keeps searching.
        if (working.isNotEmpty()) {
            if (busy?.isActive == true && progress.value.title == "Фоновая подготовка") cancel()
            val now = System.currentTimeMillis()
            val net = Net.key(app)
            recallNet(net)
            // Results older than 30 min (or from another network) may be dead: a 2–4 s check of the 12 best first,
            // otherwise the tunnel would connect to a dead server and lose much more time healing.
            // a network we know (its servers worked here and were checked within 3 h): connect at once, the tunnel re-checks itself
            val known = Store.state.value.let { s -> s.settings.netServers[net]?.let { ids -> s.servers.any { it.id in ids && s.state(it).works && now - s.state(it).checkedAt < 3 * 3600_000L } } } == true
            val stale = !known && (working.isNotEmpty() && working.none { now - st.state(it).checkedAt < 30 * 60_000L } || st.settings.netServers[net] == null && working.isNotEmpty() && st.settings.netServers.isNotEmpty())
            // Connection first; speed comparison belongs to the existing post-connect scheduler.
            deepPending = true
            if (!stale || job?.isActive == true) {
                if (working.none { now - st.state(it).checkedAt < 6 * 3600_000L }) deepPending = true
                if (selectBest(10, close = true, among = working) > 0) { ready(); return }
            }
            // A cached primary can start while revalidation runs; do not distribute traffic over slow reserves.
            // A changed set requires reconnecting the core; existing TCP sessions may restart.
            if (selectBest(8, close = false, among = working) > 0) {
                ready()
                launch("Авто: быстрая проверка") { preflight(ready, connected = true) }
                return
            }
        }
        Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Готовлю серверы…", check = "Проверяю HTTPS и Telegram MTProto; время зависит от сети и доступности серверов")
        if (busy?.isActive == true) cancel()
        autoPrepare(ready) { expectedNetwork.set(Net.key(app) to Net.underlying(app)?.toString()) }
    }

    /** «Подключение…» tapped again while Auto is still preparing: stop preparing. */
    fun cancelPrepare() {
        autoGeneration.incrementAndGet()
        if (progress.value.title.startsWith("Авто")) cancel()
        if (Tunnel.status.value.state == Tunnel.State.CONNECTING && Tunnel.socks == null) Tunnel.status.value = Tunnel.Status()
    }

    fun online(): Boolean = runCatching {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val network = Net.underlying(app)
        network != null && cm?.getNetworkCapabilities(network)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /**
     * Auto mode, the connection stopped working: find something that works instead of giving up.
     * level 1 — fresh HTTPS+MTProto verification of spares; 2 — next host wave and light Hello rescue;
     * 3 — bounded subscription rescue and the next small wave. Returns the number of servers selected (0 = nothing found).
     */
    suspend fun autoRescue(failed: Collection<Server>, level: Int, attempted: MutableSet<String> = mutableSetOf(),
        onStep: (String) -> Unit = {}): Int = coroutineScope {
        val now = System.currentTimeMillis()
        val net=Net.key(app);val handle=Net.underlying(app)?.toString()
        fun valid()=Net.key(app)==net && Net.underlying(app)?.toString()==handle
        val ids=failed.mapTo(HashSet()){it.id}
        if(ids.isNotEmpty()) Store.setStates(failed.associate {server -> server.id to {x:com.vlesscardvpn.model.ServerState ->
            if(valid()) x.copy(realMs=0,checkedAt=now) else x } })
        fun selectFresh():Int {
            if(!valid()) return 0
            val st=Store.state.value
            val fresh=AutoEligibility.servicePool(preferred(st,st.servers.filter {
                it.id !in ids && st.state(it).works && st.state(it).checkedAt>=now
            }),{st.state(it)},net,System.currentTimeMillis())
            return if(fresh.isEmpty()) 0 else selectBest(1,close=true,among=fresh)
        }
        val sourceRound=attempted.size/ConnectPolicy.FIRST
        val refresh=if(level>=3) launch(Bg.io) {
            kotlinx.coroutines.withTimeoutOrNull(AutoDiscoveryPolicy.SOURCE_MS+1000L) {
                refreshAutoSources(sourceRound) { }
            }
        } else null
        try {
            kotlinx.coroutines.withTimeoutOrNull(18_000L) {
                val st=Store.state.value
                val spares=ServerChoice.diverse(preferred(st,st.servers.filter {
                    it.id !in ids && it.id !in attempted && st.state(it).works
                }),4)
                if(spares.isNotEmpty()) {
                    onStep("проверяю резерв: HTTPS и Telegram")
                    attempted.addAll(spares.map {it.id})
                    realTest(spares,"Авто: проверяю резерв",big=false,youtube=false,telegram=true,
                        attempts=1,parallel=4,budgetMs=6500L,foreground=true,stopAfter=1)
                }
                if(selectFresh()>0) return@withTimeoutOrNull
                if(level>=2 && valid()) {
                    onStep("проверяю следующую порцию доступных хостов")
                    // Do not exclude all cloud-blocked endpoints or return to the same failed primary.
                    attempted.addAll(ids)
                    quickTest(Store.state.value.servers,attempted=attempted,firstWaveOnly=true,allowMaskRescue=false)
                }
            }
            val selected=selectFresh()
            if(selected>0 || level<2 || !valid()) return@coroutineScope selected
            onStep("проверяю лёгкую маскировку без смены работающего TUN")
            val st=Store.state.value
            val rescue=quickMaskRescue(ServerChoice.diverse(preferred(st,st.servers).filter {
                it.id in ids || it.id in attempted
            },2),net,handle)
            if(rescue!=null && valid()) return@coroutineScope selectBest(1,among=listOf(rescue),close=true)
            0
        } finally { refresh?.cancel(); refresh?.join() }
    }

    /** Recheck current routes separately, then bounded alternatives; no direct fallback or account login. */
    suspend fun repairTelegram(used: List<Server>, preferAlternative: Boolean = false): Int {
        val snapshot = Store.state.value
        val net = Net.key(app); val handle = Net.underlying(app)?.toString()
        val stamp = System.currentTimeMillis()
        val current = used.filter(AutoEligibility::vlessServer).take(4)
        val alternatives = preferred(snapshot, snapshot.servers.filter { s ->
            AutoEligibility.vlessServer(s) && used.none { it.id == s.id }
        }).sortedWith(compareBy<Server> { TelegramRouteMemory.cooling(net,it.id,snapshot.state(it).maskFor(net),stamp) }
            .thenBy { AutoEligibility.telegramRank(snapshot.state(it),net,stamp) }.thenBy { snapshot.state(it).score })
        val candidates = (current + ServerChoice.diverse(alternatives, 8)).distinctBy { it.id }
        val passed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        kotlinx.coroutines.withTimeoutOrNull(18_000L) {
            realTest(candidates, "Авто: восстанавливаю Telegram MTProto", big = false, youtube = false,
                telegram = true, attempts = 1, parallel = 4, budgetMs = 6500L, foreground = true,
                onSuccess = { passed.add(it.id) })
        }
        if (Net.key(app) != net || Net.underlying(app)?.toString() != handle) return 0
        val ready = candidates.filter { it.id in passed }
        val preferred = if (preferAlternative) ready.filter { s -> used.none { it.id == s.id } }.ifEmpty { ready } else ready
        return if (preferred.isEmpty()) 0 else selectBest(1, among = preferred, close = true)
    }

    /** Dead servers that could be revived through another Cloudflare edge (Revive). */
    fun deadCdn(st: AppState = Store.state.value): List<Server> = st.servers.filter { st.state(it).realMs == 0 && Revive.cdnLike(it) && " · CDN " !in it.name }

    /** «Оживить серверы»: dead CDN servers tried on other Cloudflare addresses; the copies that pass are kept. */
    fun revive() = launch("Оживляю серверы") {
        val dead = deadCdn()
        if (dead.isEmpty()) return@launch "Нечего оживлять: среди нерабочих нет серверов за Cloudflare (ws / xhttp / grpc + TLS). Остальным помогает «Подобрать маскировку»."
        val n = doRevive(dead, 10)
        if (n > 0) "Ожило серверов: $n (копии на чистых адресах Cloudflare, в списке с пометкой «· CDN»). Выбираю их." .also { selectBest(10, close = true) }
        else "Проверил ${minOf(dead.size, 10)} нерабочих серверов за Cloudflare на 8 других адресах — ни один не ожил (скорее всего, сервер выключен у владельца)."
    }

    /** Returns the number of revived copies kept (max 2 per original). */
    suspend fun doRevive(dead: List<Server>, limit: Int = 6): Int {
        val cand = withContext(Bg.io) { dead.filter { Revive.cdnLike(it) }.distinctBy { Revive.routeName(it) + it.secret + it.path }.take(limit * 2).filter { Revive.behindCloudflare(it) }.take(limit) }
        if (cand.isEmpty()) return 0
        val addrs = Revive.pick(8)
        val vars = cand.flatMap { Revive.variants(it, addrs) }
        Store.addServers(vars)
        realTest(vars, "Оживляю серверы через Cloudflare", big = false, attempts = 1)
        val now = Store.state.value
        val keep = vars.filter { now.state(it).works }.groupBy { it.name.substringBefore(" · CDN") }
            .flatMap { (_, l) -> l.sortedBy { now.state(it).score }.take(2) }.map { it.id }.toSet()
        val drop = vars.map { it.id }.filter { it !in keep }.toSet()
        Revive.remember(vars.filter { it.id in keep }.map { it.address })
        Store.update { st -> st.copy(servers = st.servers.filter { it.id !in drop }, states = st.states - drop) }
        return keep.size
    }

    /**
     * Network changed (Auto): compare up to eight remembered/cloud/diverse routes outside the tunnel,
     * then sequentially sample downloads on up to three finalists. Results are guarded by physical network identity.
     */
    suspend fun quickRecheck(previousCandidates: List<Server> = emptyList()): Boolean {
        val result = kotlinx.coroutines.withTimeoutOrNull(AutoHandoffPolicy.TOTAL_MS) {
            quickRecheckWithinDeadline(previousCandidates)
        }
        if (result != true) deepPending = true
        return result == true
    }

    private suspend fun quickRecheckWithinDeadline(previousCandidates: List<Server>): Boolean {
        val net = Net.key(app); val handle = Net.underlying(app)?.toString()
        Store.update { state -> state.copy(states = state.states.mapValues { (_, value) -> AutoHandoffPolicy.resetRuntime(value,net) }) }
        fun valid() = Net.key(app) == net && Net.underlying(app)?.toString() == handle
        val st = Store.state.value
        val local = st.settings.netServers[net].orEmpty().toSet()
        val remembered = st.servers.filter { it.id in local }
        val ranked = preferred(st, st.servers).sortedWith(compareByDescending<Server> {
            Brain.p(net, it, st.state(it))
        }.thenByDescending { CloudSpeed.prior(it) ?: .5 })
        val candidates = AutoHandoffPolicy.candidates(preferred(st,previousCandidates),st.servers,
            remembered + st.selected + CloudSpeed.pick(ranked, emptyList(), n = 2) + ranked)
        if (candidates.isEmpty()) return false
        val passed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val prev = progress.value
        try {
            kotlinx.coroutines.withTimeoutOrNull(8500L) {
                realTest(candidates, "Новая сеть: HTTPS и Telegram MTProto", big = false, youtube = false,
                    telegram = true, foreground = true, stopAfter = 1,
                    attempts = 1, parallel = 4, budgetMs = 6500L, onSuccess = { passed.add(it.id) })
            }
            if (!valid()) return false
            val snapshot=Store.state.value
            val ok = AutoEligibility.servicePool(candidates.filter { it.id in passed && snapshot.state(it).works },
                {snapshot.state(it)},net,System.currentTimeMillis())
            if (ok.isEmpty()) { deepPending = true; return false }
            deepPending = true
            return selectBest(1, close = false, among = ok) > 0
        } finally { progress.value = prev.copy(running = false) }
    }

    private fun selectionNetworkChanged(): String {
        if (Tunnel.status.value.state == Tunnel.State.CONNECTING)
            Tunnel.status.value = Tunnel.Status(Tunnel.State.ERROR, "Сеть изменилась", check = "Нажмите Авто для проверки новой сети")
        return "Сеть изменилась: повторите Авто"
    }

    /** Instant start on old results: the selected + best known servers checked once (no big download), then connect. */
    private suspend fun preflight(onReady: () -> Unit, connected: Boolean = false): String {
        val st = Store.state.value
        val net = Net.key(app);val network = Net.underlying(app)?.toString();val stamp = System.currentTimeMillis()
        val local = preferred(st, st.servers.filter { st.state(it).works })
            .sortedWith(ServerChoice.downloadOrder({ st.state(it) },net,stamp))
        val eligible = preferred(st,st.servers).filter { !probeMemory.cooling(net,it.id,st.state(it).maskFor(net),stamp) }
        val affinity = SourceAffinity.order(eligible,st.servers,{st.state(it)},net,stamp) { CloudControl.sources(it) }
        val cloud = (CloudSpeed.pick(eligible,emptyList(),n=4) + affinity.take(4) + CloudControl.ranked(eligible,8)).distinctBy { it.id }.take(20)
        val cand = PublicCloudQueue.merge(local,cloud,(local+eligible).distinctBy { it.id },24,slots=8)
        CloudSpeed.status.value = "Облако: ${CloudControl.count()} конфигураций; каталог ${eligible.size}; очередь волны ${cand.size}, остальные проверяются в фоне"
        // Check the first bounded wave; continue if fewer than eight routes passed.
        val passed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        realTest(cand.take(12), "Авто: HTTPS и Telegram", big = false, attempts = 1, youtube = false, telegram = true, budgetMs = 6500L, stopAfter=if(connected) Int.MAX_VALUE else 1, foreground=!connected,onSuccess={ passed.add(it.id) })
        if (cand.size > 12 && passed.isEmpty()) realTest(cand.drop(12).take(12), "Авто: HTTPS и Telegram", big = false, attempts = 1, youtube = false, telegram = true, budgetMs = 6500L, stopAfter=if(connected) Int.MAX_VALUE else 1, foreground=!connected,onSuccess={ passed.add(it.id) })
        if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return selectionNetworkChanged()
        val ok = cand.filter { it.id in passed }
        if (ok.isNotEmpty()) {
            deepPending = true
            val before = Store.state.value.let { snapshot -> snapshot.selected.map {
                AutoRoutePolicy.configuration(it,snapshot.state(it),net)
            }.toSet() }
            val extra = preferred(st, st.servers.filter { server -> cand.none { it.id == server.id } })
                .sortedWith(compareByDescending<Server> { CloudSpeed.speedOf(Report.serverKey(it)) }
                    .thenByDescending { CloudSpeed.prior(it) })
            if(connected) chooseCompared(ok,null,extra) ?: return "Сравнение прервано: повторите Авто"
            val primary = ServerChoice.automaticPrimary(st.settings.mode == Mode.AUTO,st.settings.balance == com.vlesscardvpn.model.Balance.LEAST_PING,GameSession.active())
            selectBest(10, close = true, among = ok)
            val after = Store.state.value.let { snapshot -> snapshot.selected.map {
                AutoRoutePolicy.configuration(it,snapshot.state(it),net)
            }.toSet() }
            // Reconnect only when selection changed; existing TCP sessions may restart.
            if (!connected || after != before) withContext(Dispatchers.Main) { onReady() }
            return if (connected) "Авто: лучший из проверенных — ${after.size} из ${ok.size} рабочих" + (if (after != before) ", обновил маршрут" else "")
                else "Авто: работают ${ok.size} из ${cand.size} — подключаюсь"
        }
        if (!connected) Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Готовлю серверы…", check = "Быстрые маршруты не подошли — продолжаю поиск источников и резервов; срок зависит от сети")
        return doAutoPrepare(onReady)
    }

    private fun autoPrepare(onReady: () -> Unit, setNetwork: () -> Unit) = launch("Авто-настройка") {
        val statistics=AutoSearchStatistics()
        val outcome=AutoSearchCampaign.runPersistent(network = { Net.key(app) + "|" + Net.underlying(app)?.toString() },
            online = { online() }, elapsed = android.os.SystemClock::elapsedRealtime,
            restMs = { SearchPacingPolicy.rest(PhonePower.refresh(app)) },
            exhausted = { attempted -> Store.state.value.let { st -> preferred(st, st.servers).none { it.id !in attempted } } },
            waiting = { _, _ -> },
            onWait = { wait ->
                progress.value = progress.value.copy(title = SearchFeedback.title(wait), done = 0, total = 0,
                    message = SearchFeedback.message(wait, statistics.snapshot()))
                Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING,
                    if(wait.reason==AutoSearchCampaign.WaitReason.OFFLINE) "Жду сеть…" else "Продолжаю поиск…",
                    checkOk = null)
            }) { round, attempted ->
            setNetwork()
            progress.value = progress.value.copy(title="Авто: проверяю маршруты",done=0,total=0,
                message="Источники и сохранённые серверы проверяются параллельно. Рабочий маршрут ещё не подтверждён.")
            refreshPublicCloud(foreground = true)
            var selected = false
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val network = Net.key(app) to Net.underlying(app)?.toString()
            try {
                val message = doAutoPrepare(onReady, attempted, continuous = true, round = round, statistics = statistics) { selected = true }
                val stale = network != (Net.key(app) to Net.underlying(app)?.toString())
                AutoDiagnostics.record(app, Net.family(network.first), if (stale) "STALE" else if (selected) "PASS" else "UNKNOWN",
                    if (stale) "NETWORK_CHANGED" else if (selected) "NONE" else "NO_CANDIDATE", android.os.SystemClock.elapsedRealtime() - startedAt)
                if (selected && !stale) message else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                AutoDiagnostics.record(app, Net.family(network.first), "CANCELLED", "CANCELLED", android.os.SystemClock.elapsedRealtime() - startedAt)
                throw e
            }
        }
        AutoDiagnostics.recordSearch(app,Net.family(Net.key(app)),"FOUND",outcome.waves,statistics.snapshot())
        outcome.value ?: error("Persistent search returned without a winner")
    }

    private data class AutoSourceAttempt(val at: Long, val failed: Boolean)
    private val autoSourceAttempts = java.util.concurrent.ConcurrentHashMap<String, AutoSourceAttempt>()

    /** Rescue is independent of the scheduled 12h full refresh; manual opt-out still applies. */
    private suspend fun refreshAutoSources(round: Int = 0, onBatch: (List<Server>) -> Unit) = coroutineScope {
        val settings = Store.state.value.settings
        val network = Net.underlying(app)?.toString()
        val net = Net.key(app)
        val now = android.os.SystemClock.elapsedRealtime()
        if (!settings.autoUpdateSubs) return@coroutineScope
        val sources = AutoDiscoveryPolicy.sources(settings.subscriptions, Net.family(net).startsWith("Моб.: "), round)
            .filter { url ->
                val attempt = autoSourceAttempts[net + "|" + url]
                AutoDiscoveryPolicy.refreshDue(true, now, attempt?.at, attempt?.failed == true)
            }
        sources.forEach { autoSourceAttempts[net + "|" + it] = AutoSourceAttempt(now, failed = false) }
        if (autoSourceAttempts.size > 256) autoSourceAttempts.entries.sortedBy { it.value.at }
            .take(autoSourceAttempts.size - 256).forEach { autoSourceAttempts.remove(it.key, it.value) }
        kotlinx.coroutines.withTimeoutOrNull(AutoDiscoveryPolicy.SOURCE_MS + 1000) {
            sources.map { url -> async(Bg.io) {
                val key = net + "|" + url
                var useful = false
                try {
                    val mirrors = Settings.mirrors(url)
                    // Two hosts in parallel; don't spend the deadline on sequential blocked mirrors.
                    val urls = listOfNotNull(mirrors.firstOrNull(), mirrors.firstOrNull { it.contains("cdn.jsdelivr.net/") }
                        ?: mirrors.getOrNull(1)).distinct()
                    val list = AutoSubscriptionFetch.firstUseful(urls) { address ->
                        AutoSubscriptionFetch.text(address)?.let { body ->
                            LinkParser.parseMany(body, url).filter(AutoEligibility::vlessServer).take(MAX_PER_SUB).takeIf { it.isNotEmpty() }
                        }
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (list != null && Net.key(app) == net && Net.underlying(app)?.toString() == network &&
                        Store.state.value.settings.let { it.autoUpdateSubs && url in it.subscriptions }) {
                        Store.replaceSource(url, list)
                        onBatch(list)
                        useful = true
                    }
                } finally {
                    autoSourceAttempts[key] = AutoSourceAttempt(android.os.SystemClock.elapsedRealtime(), failed = !useful)
                }
            } }.awaitAll()
        }
    }

    private suspend fun doAutoPrepare(onReady: () -> Unit, attempted: MutableSet<String> = mutableSetOf(),
        continuous: Boolean = false, round: Int = 0, statistics: AutoSearchStatistics? = null, onSelected: () -> Unit = {}): String = coroutineScope {
        val now = System.currentTimeMillis()
        val notes = mutableListOf<String>()
        val started = ConnectPolicy.StartGate()
        val net = Net.key(app)
        val handle = Net.underlying(app)?.toString()
        fun sameNetwork() = Net.key(app) == net && Net.underlying(app)?.toString() == handle
        val firstReady: (Server) -> Unit = first@ { server ->
            if (!sameNetwork()) return@first
            if (started.claimSelected {
                val n = selectBest(1, close = false, among = listOf(server))
                val snapshot = Store.state.value
                val net = Net.key(app)
                val committed = snapshot.selected.any { it.id == server.id && snapshot.state(it).works &&
                    AutoEligibility.vlessServer(it) && AutoEligibility.serviceRank(snapshot.state(it), net, System.currentTimeMillis()) != 2 }
                if (com.vlesscardvpn.BuildConfig.DEBUG) android.util.Log.i("E2E",
                    "auto handoff selected=$n committed=$committed working=${snapshot.servers.count { snapshot.state(it).works }}")
                n > 0 && committed
            }) {
                deepPending = true
                onSelected()
                scope.launch(Dispatchers.Main) { if (sameNetwork()) onReady() }
            }
        }
        fun fresh(): List<Server> = Store.state.value.let { st ->
            AutoEligibility.servicePool(preferred(st, st.servers.filter {
                st.state(it).works && st.state(it).checkedAt >= now
            }), { st.state(it) }, net, System.currentTimeMillis())
        }
        val batches = kotlinx.coroutines.channels.Channel<List<Server>>(AutoDiscoveryPolicy.SOURCES)
        val refresh = launch(Bg.io) {
            try { refreshAutoSources(round) { batches.trySend(it) } }
            finally { batches.close() }
        }
        try {
            // Fetch immediately, alongside cached-catalog probes. First useful source wins;
            // one blocked subscription cannot hold the connect button for all other sources.
            kotlinx.coroutines.withTimeoutOrNull(AutoDiscoveryPolicy.FIRST_CONNECT_MS) {
                ForegroundDiscoveryPump.run(batches, cached = { Store.state.value.servers },
                    keepGoing = { !started.started && sameNetwork() },
                    usable = { it.id !in attempted && AutoEligibility.vlessServer(it) }) { batch ->
                    notes += quickTest(batch, firstReady, attempted, firstWaveOnly = true, allowMaskRescue = false, statistics = statistics)
                }
            }
            // A separate small budget: a slow native wave/source cannot silently starve mask rescue.
            // Probe reachable alternatives before costly mask evolution; no TCP-positive prerequisite.
            if (continuous && !started.started && round % 3 == 0 && sameNetwork()) {
                val st = Store.state.value
                val candidates = ServerChoice.diverse(preferred(st,st.servers).filter {
                    it.id in attempted && !st.state(it).works
                }.sortedBy { if(st.state(it).tcpMs>0) 0 else 1 },2)
                quickMaskRescue(candidates,net,handle)?.let(firstReady)
            }
            if (sameNetwork()) fresh().firstOrNull()?.let(firstReady)
            if (started.started) return@coroutineScope "HTTPS и Telegram подтверждены — подключаюсь; подробные проверки в фоне"
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!sameNetwork()) return@coroutineScope if (continuous) "Сеть изменилась — начинаю следующую волну" else selectionNetworkChanged()
            progress.value = progress.value.copy(title = "Авто: проверяю резервные маршруты")
            Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Продолжаю поиск…",
                check = "Быстрая проверка HTTPS и Telegram не нашла маршрут. Проверяю источники, резерв и маскировки; кнопка отменяет поиск.")
            if (!continuous) kotlinx.coroutines.withTimeoutOrNull(AutoDiscoveryPolicy.RECOVERY_MS) {
                // At most one bounded reserve pass, not a TCP sweep of every imported link.
                notes += quickTest(Store.state.value.servers, firstReady, attempted)
                if (!started.started && fresh().isEmpty() && sameNetwork()) {
                    val st = Store.state.value
                    val candidates = ServerChoice.diverse(preferred(st, st.servers).filter {
                        st.state(it).tcpMs > 0 && !st.state(it).works
                    }.sortedWith(compareByDescending<Server> { CloudSpeed.prior(it) ?: .0 }.thenBy { st.state(it).tcpMs }), 2)
                    if (candidates.isNotEmpty()) notes += doFindMasks(candidates, 12, enough = 1, budgetMs = 8000)
                }
            }
            if (started.started) return@coroutineScope "Рабочий HTTPS-маршрут найден — подключаюсь"
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!sameNetwork()) return@coroutineScope if (continuous) "Сеть изменилась — начинаю следующую волну" else selectionNetworkChanged()
            val working = fresh()
            if (working.isNotEmpty() && selectBest(1, close = false, among = working) > 0) {
                deepPending = true
                onSelected()
                withContext(Dispatchers.Main) { if (sameNetwork()) onReady() }
                return@coroutineScope "Авто: проверенный VLESS-маршрут выбран — подключаюсь"
            }
            if (continuous) return@coroutineScope "Продолжаю поиск следующей порцией серверов; прямого обхода нет"
            val message = if (Store.state.value.settings.autoUpdateSubs)
                "Авто попытался обновить подписки и проверил ограниченный набор маршрутов. Рабочий маршрут пока не подтверждён; прямого обхода нет. Повторите поиск после смены сети или проверьте доступ к источникам."
                else "Рабочий маршрут не подтверждён. Автообновление подписок выключено в настройках; прямого обхода нет."
            Tunnel.status.value = Tunnel.Status(Tunnel.State.ERROR, "Пока нет подтверждённого маршрута ≤500 мс", check = message, checkOk = false)
            message
        } finally {
            refresh.cancel()
            refresh.join()
            batches.cancel()
        }
    }

    /** Cached cloud/network candidates first; no whole-catalog TCP scan or video downloads before first connect. */
    suspend fun quickTest(list: List<Server>, onFirst: ((Server) -> Unit)? = null, attempted: MutableSet<String> = mutableSetOf(), firstWaveOnly: Boolean = false, allowMaskRescue: Boolean = true, statistics: AutoSearchStatistics? = null): String {
        CloudSpeed.loadCached(app)
        val st = Store.state.value
        val untried = list.filter { it.id !in attempted }
        if (untried.isEmpty()) return "Эти маршруты уже были в очереди текущего поиска; отменённые пробы не считаются отказом"
        val net = Net.key(app); val network = Net.underlying(app)?.toString()
        val allEligible = preferred(st, untried).sortedBy { CloudSpeed.blocked(it) }
        // Apply cheap cooldown/service eligibility across the catalog BEFORE truncating ranking.
        // Otherwise 256 cooled/failed leaders can hide every fresh route behind them forever.
        val time = System.currentTimeMillis()
        val serviceRanks=allEligible.associate { it.id to AutoEligibility.telegramRank(st.state(it),net,time) }
        val coolingIds=allEligible.filter { server ->
            val e=st.state(server).evidenceFor(net)
            TelegramRouteMemory.cooling(net,server.id,st.state(server).maskFor(net),time) ||
                probeMemory.cooling(net,server.id,st.state(server).maskFor(net),time) ||
                (e.streak>0 && time>=e.at && time-e.at<ProbeMemory.cooldownMs(e.streak))
        }.mapTo(HashSet()) {it.id}
        val readyCatalog=DiscoveryWindow.pool(allEligible,{it.id in coolingIds},{serviceRanks.getValue(it.id)})
        val rememberedIds = (st.settings.netServers[net] ?: st.settings.netServers[Net.family(net)]).orEmpty().toSet()
        val eligible = if (firstWaveOnly) (CloudControl.ranked(readyCatalog, 32) +
            readyCatalog.filter { it.id in rememberedIds }.take(32) + TransportExploration.representatives(readyCatalog) +
            ServerChoice.diverse(readyCatalog, AutoDiscoveryPolicy.RANKING_WINDOW))
            .distinctBy { it.id }.take(AutoDiscoveryPolicy.RANKING_WINDOW) else readyCatalog
        val affinity = SourceAffinity.order(eligible,Store.state.value.servers,{Store.state.value.state(it)},net,System.currentTimeMillis()) { CloudControl.sources(it) }
        val cloud = (CloudSpeed.pick(eligible, emptyList(), n = 4) + affinity.take(4) + CloudControl.ranked(eligible,8)).distinctBy { it.id }.take(ConnectPolicy.FIRST)
        val remembered = (st.settings.netServers[net] ?: st.settings.netServers[Net.family(net)]).orEmpty().toSet()
        val gaming = GameSession.active()
        // Score each candidate once; sorting must not repeatedly read cloud/model state.
        val learned = (if(gaming) eligible.map {server ->
            server to (ServerChoice.latencyScore(st.state(server).evidenceFor(net),st.state(server).realMs) to Brain.p(net,server,st.state(server)))
        }.sortedWith(compareBy<Pair<Server,Pair<Double,Double>>>{it.second.first}.thenByDescending{it.second.second}.thenBy{it.first.id}).map{it.first}
        else AdaptiveRouteRank.order(eligible,{st.state(it)},net,time,{CloudSpeed.prior(it)}))
            .mapIndexed {i,server -> server.id to i}.toMap()
        val first = ConnectPolicy.candidates(eligible, cloud, remembered,
            cooling = {it.id in coolingIds}, serviceRank = {serviceRanks.getValue(it.id)},
            recoveryProbeLimit = if(firstWaveOnly) 3 else 1) { s -> learned[s.id] ?: Int.MAX_VALUE }
        val cachedGood = first.filter { st.state(it).evidenceFor(net).let { e -> e.successes > 0 && e.streak == 0 && time >= e.at && time - e.at < 1800000 } }.map { it.id }.toSet()
        val ping = first.filter { it.id !in cachedGood }
        val earlyHandoff = firstWaveOnly
        val plan = PhonePower.refresh(app)
        val slots = minOf(ping.size, PowerPolicy.cap(ConnectPolicy.FIRST, plan.foregroundTcpParallel))
        progress.value = progress.value.copy(
            title = if (earlyHandoff) "Авто: HTTPS и Telegram MTProto" else "Авто: TCP-проверка ${ping.size} кандидатов",
            done = 0,
            total = ping.size,
            message = if (earlyHandoff) "До $slots параллельно · подключаю после HTTPS и ответа Telegram MTProto" else
                "Одновременно до $slots · ${cachedGood.size} с недавним подтверждением" +
                    (if (slots < minOf(ConnectPolicy.FIRST, ping.size)) " · ${plan.reason}" else ""))
        val reachable = if (ConnectPolicy.requiresTcpFilter(earlyHandoff)) Tester.fastFilter(ping, foreground = true) else emptyMap()
        if (reachable.isNotEmpty()) Store.setStates(reachable.mapValues { (_, ms) -> { old: com.vlesscardvpn.model.ServerState -> old.copy(tcpMs = ms) } })
        val available = first.filter { (reachable[it.id] ?: -1) != 0 }.ifEmpty { first }
        val ordered = available.sortedBy { if (it.id in cachedGood || (reachable[it.id] ?: -1) > 0) 0 else 1 }
        // First connect as soon as one real HTTPS route passes; compare the remaining candidates after the tunnel is up.
        val shortlist = ServerChoice.diverse(ordered, AutoEligibility.TOP)
        val passed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val firstFired = java.util.concurrent.atomic.AtomicBoolean(false)
        val firstCallback: ((Server) -> Unit)? = onFirst?.let { callback -> { server ->
            if (firstFired.compareAndSet(false, true)) callback(server)
        } }
        val fast = ConnectPolicy.fastStart()
        val fastOk = realTest(shortlist, "Авто: проверяю первый быстрый маршрут", big = ConnectPolicy.requiresBodyProof(earlyHandoff), youtube = false, telegram = st.settings.mode == Mode.AUTO,
            attempts = 1, parallel = fast.parallel, foreground = firstWaveOnly, stopAfter = if (onFirst != null || firstWaveOnly) fast.stopAfter else Int.MAX_VALUE,
            budgetMs = if (st.settings.mode == Mode.AUTO && firstWaveOnly) ConnectPolicy.FIRST_PROBE_MS else if (st.settings.mode == Mode.AUTO) 6500L else fast.budgetMs, onFirst = firstCallback, onSuccess = { passed.add(it.id) },
            onAttempt = {server->if(firstWaveOnly) attempted.add(server.id);statistics?.start()},
            onObservation = {p->statistics?.observe(p.works,p.realMs,p.tgOk,p.tgMs)})
        if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return if (firstWaveOnly) "Сеть изменилась — результаты отброшены" else selectionNetworkChanged()
        if (onFirst != null && firstFired.get()) return "Первый рабочий VLESS-маршрут найден — подключаюсь; сравнение продолжится после запуска"
        if (firstWaveOnly) {
            if(fastOk==0 && onFirst!=null && allowMaskRescue) {
                val rescued=quickMaskRescue(shortlist,net,network)
                if(rescued!=null) { firstCallback?.invoke(rescued); return "Лёгкая маскировка: HTTPS и Telegram подтверждены — подключаюсь" }
            }
            return "Первая волна: проверено ${shortlist.size}, HTTPS+MTProto маршрутов $fastOk"
        }
        if (fastOk > 0) {
            chooseCompared(shortlist.filter { it.id in passed }, onFirst, eligible.filter { s -> shortlist.none { it.id == s.id } })
            deepPending = true; return "Выбран лучший из проверенных VLESS-маршрутов"
        }
        attempted.addAll(first.map { it.id });passed.clear()
        val ok = realTest(ordered, "Авто: проверяю резервные VLESS-маршруты", big = true,
            youtube = false, telegram = st.settings.mode == Mode.AUTO, attempts = 1, stopAfter = if (onFirst != null) fast.stopAfter else Int.MAX_VALUE,
            budgetMs = if (st.settings.mode == Mode.AUTO) 6500L else fast.budgetMs, onFirst = firstCallback, onSuccess = { passed.add(it.id) })
        if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return selectionNetworkChanged()
        if (onFirst != null && firstFired.get()) return "Найден рабочий VLESS-маршрут — подключаюсь"
        if (ok > 0) { chooseCompared(ordered.filter { it.id in passed }, onFirst, eligible.filter { it.id !in attempted }); deepPending = true; return "Проверенные VLESS-маршруты готовы" }
        // Only if the first wave failed: bound DNS/TCP work to a small diverse wave, not thousands of subscriptions.
        val rest = ServerChoice.diverse(eligible.filter { it.id !in attempted }, ConnectPolicy.NEXT)
        attempted.addAll(rest.map { it.id })
        val tcp = java.util.concurrent.ConcurrentHashMap<String, Int>(); val states = StateBatch()
        Tester.tcp(rest, parallel = 32, timeoutMs = 1500) { server, ms -> tcp[server.id] = ms; states.put(server.id) { it.copy(tcpMs = ms) } }
        states.flush()
        val candidates = rest.filter { (tcp[it.id] ?: 0) > 0 }.sortedBy { tcp[it.id] }
        passed.clear()
        val second = realTest(candidates, "Авто: запасные ${candidates.size} маршрутов", big = true, youtube = false, telegram = st.settings.mode == Mode.AUTO,
            attempts = 1, stopAfter = Int.MAX_VALUE, budgetMs = ConnectPolicy.QUICK_BUDGET_MS,onSuccess={ passed.add(it.id) })
        if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return selectionNetworkChanged()
        if (second > 0) { chooseCompared(candidates.filter { it.id in passed },onFirst, eligible.filter { it.id !in attempted }); deepPending = true }
        return "Короткая проверка: работают ${ok + second}; остальные серверы не задерживают первый успех"
    }

    private fun expireUnreproducedSpeed(server: Server, net: String, at: Long) {
        Store.setState(server.id) { ServerChoice.invalidateSpeed(it, net, at) }
    }

    /** Fast rescue after the first native batch failed. No download, source refresh, mutation or full mask sweep. */
    private suspend fun quickMaskRescue(candidates:List<Server>, net:String, network:String?):Server? {
        val start=Store.state.value
        if(start.settings.mode!=Mode.AUTO) return null
        val expected=candidates.associate {it.id to start.state(it).maskFor(net)}
        val pinned=pinCertificates(candidates.take(2))
        val lab=start.settings.experimentalMaskLab
        val trialStarted=android.os.SystemClock.elapsedRealtime()
        val variants=pinned.flatMap {s->
            val available=MaskWarmStart.trials(s,expected[s.id].orEmpty(),start.settings,if(lab) 8 else 2)
            val ids=if(lab) MaskExperiment.memory.choose(net,network,s.id,available.map {it.id},trialStarted) else available.map {it.id}
            ids.mapNotNull {id->available.firstOrNull {it.id==id}}.map{s to it}
        }
        if(variants.isEmpty() || Net.key(app)!=net || Net.underlying(app)?.toString()!=network) return null
        val winner=java.util.concurrent.atomic.AtomicReference<Pair<Int,Probe>?>(null)
        progress.value=progress.value.copy(title="Авто: лёгкая маскировка Hello",message="До двух серверов · лёгкий Hello; HTTPS и Telegram MTProto ≤500 мс")
        if(lab) variants.forEach {(server,mask)->MaskExperiment.memory.schedule(net,network,server.id,mask.id,trialStarted)}
        kotlinx.coroutines.withTimeoutOrNull(3800L) {
            Tester.real(variants,start.settings.testUrl,null,youtube=false,telegram=true,requireTelegram=true,
                foreground=true,batch=4,parallel=4,attempts=1,big=false,stopAfter=1,budgetMs=3500L) {i,p->
                if(lab && p!=Tester.SKIPPED && Net.key(app)==net && Net.underlying(app)?.toString()==network) {
                    val (server,mask)=variants[i]
                    MaskExperiment.memory.observe(net,network,server.id,mask.id,trialStarted,
                        android.os.SystemClock.elapsedRealtime(),MaskExperiment.verdict(p),
                        if(AutoLatencyPolicy.accepts(p)) maxOf(p.realMs,p.tgMs) else null)
                }
                if(AutoLatencyPolicy.accepts(p)) winner.compareAndSet(null,i to p)
            }
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if(Net.key(app)!=net || Net.underlying(app)?.toString()!=network) return null
        if(Store.state.value.settings.mode!=Mode.AUTO || lab && !Store.state.value.settings.experimentalMaskLab) return null
        val (index,p)=winner.get() ?: return null
        val (server,mask)=variants[index]
        val stamp=System.currentTimeMillis()
        var committed=false
        Store.setState(server.id) {current->
            if(!MaskSamplePolicy.unchanged(current,start.state(server),net)) current else {
                committed=true
                current.copy(maskId=mask.id,netMasks=current.netMasks+(net to mask.id),realMs=p.realMs,bigOk=null,
                    kbps=0,speedCheckedAt=0,speedNetwork="",speedMask="",tgOk=true,tgMs=p.tgMs,ytOk=null,
                    serviceNetwork=net,serviceMask=mask.id,serviceCheckedAt=stamp,checkedAt=stamp,
                    okCount=current.okCount+1)
            }
        }
        if(!committed) return null
        Store.recordMasks(net,listOf(mask.id to true))
        return server
    }

    private suspend fun chooseCompared(candidates: List<Server>, onReady: ((Server) -> Unit)?,
                                       explore: List<Server> = emptyList()): Server? {
        val net = Net.key(app); val network = Net.underlying(app)?.toString()
        val deadline = android.os.SystemClock.elapsedRealtime() + AutoRoutePolicy.SEARCH_MS
        fun remaining() = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
        fun valid() = Net.key(app) == net && Net.underlying(app)?.toString() == network
        fun allowed() = !GameSession.active() && PhonePower.refresh(app).let { it.allowLight && !it.deferTraffic }
        suspend fun compare(pool: List<Server>): Server? {
            val before = Store.state.value
            val working = pool.filter { before.state(it).works && (before.settings.mode != Mode.AUTO || AutoEligibility.allowed(it)) }
            val missing = ServerChoice.diverse(working.filter { AutoEligibility.serviceRank(before.state(it),net,System.currentTimeMillis()) != 0 },6)
            if (before.settings.mode == Mode.AUTO && missing.isNotEmpty() && remaining() >= 1500) {
                kotlinx.coroutines.withTimeoutOrNull(minOf(4000L,remaining())) {
                    realTest(missing,"Авто: проверяю Telegram и YouTube",big=false,youtube=true,attempts=1,parallel=6,
                        budgetMs=minOf(ConnectPolicy.QUICK_BUDGET_MS,remaining()))
                }
            }
            if (!valid()) return null
            val snapshot=Store.state.value
            val live=working.filter { snapshot.state(it).works }
            val acceptable = if (before.settings.mode == Mode.AUTO) AutoEligibility.servicePool(live,{snapshot.state(it)},net,System.currentTimeMillis()) else live
            if (remaining() < AutoSelection.MIN_PROBE_MS) return acceptable.minWithOrNull(
                ServerChoice.downloadOrder({ snapshot.state(it) },net,System.currentTimeMillis()))
            return AutoSelection.compare(acceptable, { Store.state.value.state(it) }, net,
                now = { System.currentTimeMillis() }, elapsed = { android.os.SystemClock.elapsedRealtime() },
                validNetwork = { valid() },
                allowDownload = { allowed() && remaining() >= AutoSelection.MIN_PROBE_MS }, gaming = GameSession.active(),
                hint = { maxOf(CloudControl.speed(it),CloudSpeed.speedOf(Report.serverKey(it))) },
                onInconclusive = { server, at -> expireUnreproducedSpeed(server,net,at) }) { server, budget ->
                val bounded = minOf(budget,remaining())
                if (bounded >= AutoSelection.MIN_PROBE_MS) kotlinx.coroutines.withTimeoutOrNull(bounded) {
                    realTest(listOf(server), "Авто: сравниваю скорость финалистов", big = true, youtube = false,
                        attempts = 1, parallel = 1, budgetMs = bounded)
                }
            }
        }
        var winner = compare(candidates) ?: run {
            if (!valid()) selectionNetworkChanged()
            return null
        }
        // A working 100 kbit/s route is a fallback, not grounds to stop looking.
        // Only one additional diverse wave; no whole-catalog scan or unbounded masks.
        if (allowed() && valid() && (AutoRoutePolicy.needsFaster(Store.state.value.state(winner),net,System.currentTimeMillis()) || AutoEligibility.serviceRank(Store.state.value.state(winner),net,System.currentTimeMillis()) == 2) && remaining() >= 2000) {
            val wave = ServerChoice.diverse(explore.filter { s -> AutoEligibility.allowed(s) && candidates.none { it.id == s.id } },8)
            val passed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            if (wave.isNotEmpty()) {
                kotlinx.coroutines.withTimeoutOrNull(minOf(3500L,remaining())) {
                    realTest(wave,"Авто: ищу быстрее, не только рабочий",big=false,youtube=false,
                        attempts=1,parallel=4,budgetMs=1200,onSuccess={ passed.add(it.id) })
                }
                if (!valid()) { selectionNetworkChanged(); return null }
                if (passed.isNotEmpty() && remaining() >= AutoSelection.MIN_PROBE_MS)
                    winner = compare(listOf(winner) + wave.filter { it.id in passed }) ?: winner
            }
        }
        if (allowed() && valid() && remaining() >= AutoSelection.MIN_PROBE_MS &&
            AutoRoutePolicy.shouldCompareMasks(Store.state.value.state(winner),net,System.currentTimeMillis())) {
            kotlinx.coroutines.withTimeoutOrNull(minOf(AutoRoutePolicy.MASK_MS,remaining())) { tuneAutoMask(winner,net,network) }
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (!valid()) { selectionNetworkChanged(); return null }
        if (onReady != null) onReady(winner)
        return winner
    }

    /** Keep the working route unless an isolated HTTPS + download trial proves a faster mask here. */
    private suspend fun tuneAutoMask(server: Server, net: String, network: String?) {
        var expected=Store.state.value.state(server)
        val settings=Store.state.value.settings
        if(settings.mode!=Mode.AUTO) return
        val pinned=Store.state.value.servers.firstOrNull{it.id==server.id} ?: return
        fun valid():Boolean = Net.key(app)==net && Net.underlying(app)?.toString()==network &&
            !SessionHold.active() && !GameSession.active() && PhonePower.refresh(app).let{it.allowLight && !it.deferTraffic} &&
            Store.state.value.settings.mode==Mode.AUTO
        suspend fun sample(id:String):Probe? {
            if(!valid()) return null
            var result:Probe?=null
            kotlinx.coroutines.withTimeoutOrNull(2100L) {
                Tester.real(listOf(pinned to Masks.byId(id)),settings.testUrl,null,
                    youtube=false,telegram=true,requireTelegram=true,batch=1,parallel=1,attempts=1,big=true,budgetMs=2000) {_,p->result=p}
            }
            return result?.takeIf {valid() && AutoLatencyPolicy.accepts(it) && it.bigOk==true && it.kbps>0}
        }
        // A speed-only replacement needs a measured current baseline, not an old/global kbps hint.
        if(ServerChoice.measuredSpeed(expected,net,System.currentTimeMillis())<=0) {
            val baseline=sample(expected.maskFor(net)) ?: return
            var committed=false
            val prior=expected
            Store.setState(server.id) {current->
                if(valid() && MaskSamplePolicy.unchanged(current,prior,net)) {
                    committed=true
                    MaskSamplePolicy.adopt(current,net,prior.maskFor(net),System.currentTimeMillis(),baseline,true,false,true)
                } else current
            }
            if(!committed) return
            expected=Store.state.value.state(server)
        }
        for(id in AutoRoutePolicy.maskTrials(pinned,expected.maskFor(net),settings)) {
            if(!valid()) return
            val first=sample(id) ?: continue
            if(!AutoRoutePolicy.improved(expected,net,System.currentTimeMillis(),first)) continue
            val second=sample(id) ?: continue
            val confirmed=MaskSamplePolicy.confirmed(first,second) ?: continue
            val prior=expected
            var committed=false
            Store.setState(server.id) {current->
                if(valid() && MaskSamplePolicy.unchanged(current,prior,net) && AutoRoutePolicy.improved(current,net,System.currentTimeMillis(),confirmed)) {
                    committed=true
                    MaskSamplePolicy.adopt(current,net,id,System.currentTimeMillis(),confirmed,true,false,true)
                } else current
            }
            if(committed) expected=Store.state.value.state(server)
        }
    }

    /** Find a VERIFIED spare while the existing TUN stays up. Selection changes only after returning success. */
    suspend fun prepareStandby(used: List<Server>): Server? {
        if (job?.isActive == true) return null
        val plan=PhonePower.refresh(app)
        if(SessionHold.active() || GameSession.active() || !plan.allowLight || plan.deferTraffic) return null
        val st = Store.state.value; val ids = used.map { it.id }.toSet(); val net = Net.key(app)
        if(!ReserveBudget.take(net+"|"+Net.underlying(app)?.toString(),android.os.SystemClock.elapsedRealtime())) return null
        val stamp=System.currentTimeMillis()
        val pool = preferred(st, st.servers.filter { it.id !in ids }).filter {server->
            !probeMemory.cooling(net,server.id,st.state(server).maskFor(net),stamp) &&
            !TelegramRouteMemory.cooling(net,server.id,st.state(server).maskFor(net),stamp) &&
            AutoEligibility.telegramRank(st.state(server),net,stamp)!=2
        }
        val now = System.currentTimeMillis()
        val total = pool.sumOf { st.state(it).evidenceFor(net).checks.coerceIn(0, 10000) }.coerceAtMost(1_000_000)
        val ranked = AdaptiveRouteRank.order(pool,{st.state(it)},net,now,{CloudSpeed.prior(it)})
        val exploit = ServerChoice.diverse(ranked, 5)
        // One exploration slot, only for background verification; never an unverified live switch.
        val explore = pool.filter { s -> exploit.none { it.id == s.id } }.maxByOrNull {
            ServerChoice.blended(CloudSpeed.prior(it), st.state(it).evidenceFor(net), now) +
                .08 * ServerChoice.exploration(st.state(it).evidenceFor(net).checks, total)
        }
        val candidates = exploit + listOfNotNull(explore)
        var found: Server? = null
        val previous = progress.value
        try { realTest(candidates,"Фоновый запасной маршрут",big=false,youtube=false,telegram=st.settings.mode==Mode.AUTO,attempts=1,stopAfter=1,
            budgetMs=ConnectPolicy.QUICK_BUDGET_MS,onFirst={ found=it }) }
        finally { progress.value=previous }
        return found
    }

    /** New migration source loads on launch even if the old subscriptions were refreshed recently.
     * Never delays a working VPN, resets the all-source refresh timestamp, or restores a removed source.
     */
    private fun maybeLoadRequestedSource() {
        val st = Store.state.value
        val source = listOf(com.vlesscardvpn.model.Subs.CLOUD_WORKS,com.vlesscardvpn.model.Subs.DIMAN).firstOrNull { u -> u in st.settings.subscriptions && st.servers.none { it.source == u } } ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!st.settings.autoUpdateSubs || source !in st.settings.subscriptions ||
            st.servers.any { it.source == source } || requestedSourceJob?.isActive == true ||
            now - requestedSourceAttempt < 60000L) return
        requestedSourceAttempt = now
        requestedSourceJob = scope.launch(Bg.io) {
            val got = fetchFull(source) ?: return@launch
            val list = LinkParser.parseMany(got.first,source).take(MAX_PER_SUB)
            if (list.isNotEmpty() && source in Store.state.value.settings.subscriptions) {
                Store.replaceSource(source,list)
                got.second?.let { info -> Store.update { it.copy(settings = it.settings.copy(subInfo = it.settings.subInfo + (source to info))) } }
            }
        }
    }

    /** Subscriptions older than 12 h are refreshed in the background on app start. */
    fun maybeAutoRefresh() {
        val st = Store.state.value.settings
        if (st.autoUpdateSubs && System.currentTimeMillis() - st.lastSubRefresh > 12 * 3600_000L) refreshSubscriptions()
    }

    /** «Страна»: only servers of the chosen country when at least one of them works, otherwise all. */
    fun preferred(st: AppState, list: List<Server>): List<Server> {
        val remote = list.filter(ServerOnlyPolicy::userRoute)
        val safe = if (st.settings.mode == Mode.AUTO) remote.filter(AutoEligibility::allowed) else remote
        val c = st.settings.country
        if (c.isEmpty()) return safe
        return safe.filter { Countries.of(it.name) == c }.ifEmpty { safe }
    }

    /** Countries of the working servers, most servers first: (ISO, count). */
    fun countries(st: AppState): List<Pair<String, Int>> = st.servers.filter { st.state(it).works }.groupingBy { Countries.of(it.name) }.eachCount()
        .filterKeys { it.isNotEmpty() }.entries.sortedByDescending { it.value }.map { it.key to it.value }

    fun toggleFavorite(id: String) = Store.update { st -> val f = st.settings.favorites
        st.copy(settings = st.settings.copy(favorites = if (id in f) f - id else f + id)) }

    /**
     * [close]: only servers about as good as the best one. The balancer («Самый быстрый») picks by ping alone, so a
     * slow server with a lower ping in the set would steal the traffic — YouTube on 1 Mbit/s. One spare stays for
     * failover if it is not hopeless (≤ 3× the best).
     */
    fun closeOnes(scores: List<Int>): Int {
        if (scores.isEmpty()) return 0
        val b = scores.first()
        return scores.count { it <= b * 1.25 + 50 }
    }

    /** Re-checks up to [n] candidates right now (1–3 s, no big download) and returns the ones that really work. */
    suspend fun verified(list: List<Server>, n: Int = 8, sort: Boolean = true): List<Server> {
        if (list.isEmpty()) return emptyList()
        val st = Store.state.value
        val l = list.distinctBy { it.id }
        val cand = (if (sort) Brain.order(Net.key(app), l) { st.state(it) } else l).take(n)
        val prev = progress.value
        try { realTest(cand, "Проверяю запасные серверы", big = false, attempts = 1) } finally { progress.value = prev }
        val now = Store.state.value
        return cand.filter { now.state(it).works }
    }

    /** Selects the [n] fastest working servers (deselects others). */
    /**
     * Load spreading: phones must not all pile onto the same server. Each phone gets its own stable ±0-20% "taste"
     * per server (from its anonymous id), so near-equal servers are picked differently on different phones, and every
     * other phone already on a server (cloud "load") costs it 25% more, up to 2×.
     */
    fun spreadScore(ss: com.vlesscardvpn.model.ServerState, s: Server, phone: String): Int {
        val base = ss.score
        if (base == Int.MAX_VALUE || phone.isEmpty()) return base
        val taste = ((phone + s.id).hashCode().toLong() and 0x7fffffff) % 1000 / 1000.0 * 0.2
        val others = ((Report.load[Report.serverKey(s)] ?: 0) - if (ss.selected) 1 else 0).coerceAtLeast(0)
        return (base * (1 + taste + minOf(1.0, others * 0.25)) * operatorFactor(Report.serverKey(s)) * ServerChoice.stayFactor(ss.selected)).toInt()
    }

    /**
     * What other phones on this operator saw (cloud «net»: [kbit/s, % worked, checks]): mostly working there → up to
     * 15% better, mostly failing → up to 2× worse. Weighted by sample count (n / (n + 6)); this phone still decides.
     */
    fun operatorFactor(key: String, net: Map<String, IntArray> = CloudSpeed.net): Double {
        val e = net[key] ?: return 1.0
        if (e.size < 3 || e[2] < 2) return 1.0
        val target = when {
            e[1] >= 80 -> 0.85
            e[1] < 25 -> 2.0
            e[1] < 50 -> 1.3
            else -> 1.0
        }
        return 1.0 + ServerChoice.evidence(e[2]) * (target - 1.0)
    }

    /** Servers where Telegram did not fail ([tg] true or not checked yet); all of them if none is left. */
    fun <T> tgCapable(list: List<T>, tg: (T) -> Boolean?): List<T> = list.filter { tg(it) != false }.ifEmpty { list }

    fun selectBest(n: Int = 10, among: Collection<Server>? = null, close: Boolean = false): Int {
        var c = 0
        val ids = among?.map { it.id }?.toHashSet()
        runCatching { CloudSpeed.loadNet(app) }
        Store.update { st ->
            // one WireGuard tunnel per key (WARP accounts): two with the same key would steal the session from each other
            val works = st.servers.filter { (ids == null || it.id in ids) && st.state(it).works }
            // the balancer spreads connections over all selected servers: one that cannot reach Telegram would break
            // every few Telegram connections («иногда не работает»), so such servers stay out while others exist
            // (only in «Авто»: in «Серверы» the user's own choice stays as it is)
            val all = if (st.settings.mode == com.vlesscardvpn.model.Mode.AUTO) AutoEligibility.servicePool(works.filter(AutoEligibility::allowed),{st.state(it)},Net.key(app),System.currentTimeMillis()) else works
            val me = runCatching { Report.loadFrom(app); Report.phoneId(app) }.getOrDefault("")
            val gaming = GameSession.active()
            val netKey = Net.key(app)
            val spread = { sv: Server -> if (gaming) ServerChoice.latencyScore(st.state(sv).evidenceFor(netKey), st.state(sv).realMs).toInt()
                else if (st.settings.mode == Mode.AUTO) ServerChoice.qualityScore(st.state(sv),netKey,System.currentTimeMillis())
                else spreadScore(st.state(sv), sv, me) }
            val primary = ServerChoice.automaticPrimary(st.settings.mode == Mode.AUTO, st.settings.balance == com.vlesscardvpn.model.Balance.LEAST_PING, gaming)
            val rankTime = System.currentTimeMillis()
            val pool = preferred(st, all)
            val ranked = if (st.settings.mode == Mode.AUTO && !gaming)
                pool.sortedWith(ServerChoice.downloadOrder({ st.state(it) },netKey,rankTime)) else pool.sortedBy(spread)
            val stable = if (st.settings.mode == Mode.AUTO) AutoPrimaryPolicy.choose(ranked,{st.state(it)},netKey,rankTime,gaming) else ranked
            val sorted = stable.distinctBy { if (it.protocol == "wireguard") "wg:" + it.secret else it.id }.take(if (st.settings.mode == Mode.AUTO) AutoEligibility.ACTIVE else if (primary) 1 else n)
            val favs = if (st.settings.mode != Mode.AUTO && close && !primary) all.filter { it.id in st.settings.favorites } else emptyList()
            val best = ((if (st.settings.mode != Mode.AUTO && close && !primary) closeOnes(sorted.map(spread)).let { k -> sorted.take(k) } else sorted) + favs).map { it.id }.toSet()
            c = best.size
            st.copy(states = st.servers.associate { s -> s.id to st.state(s).copy(selected = s.id in best) } )
        }
        return c
    }

    fun testByeDpi() = launch("Проверка маскировки VLESS") {
        val st = Store.state.value
        val servers = preferred(st, st.servers.filter { st.state(it).works && ServerOnlyPolicy.userRoute(it) }).take(4)
        if (servers.isEmpty()) return@launch "Для проверки нужен рабочий VLESS-сервер. Прямой обход отключён."
        doFindMasks(servers, perServer = 12)
    }

    data class DpiResult(val ok: Boolean, val ms: Int, val error: String, val score: Int = -1)
    val dpiResults = MutableStateFlow<Map<String, DpiResult>>(emptyMap())

    fun findDpi() = launch("Подбор маскировки VLESS") {
        val st = Store.state.value
        val servers = preferred(st, st.servers.filter { st.state(it).works && ServerOnlyPolicy.userRoute(it) }).take(4)
        if (servers.isEmpty()) "Для подбора нужна рабочая VLESS-конфигурация. Прямой обход отключён."
        else doFindMasks(servers, perServer = 24)
    }

    suspend fun doFindDpi(): String {
        val st = Store.state.value
        val servers = preferred(st, st.servers.filter { st.state(it).works && ServerOnlyPolicy.userRoute(it) }).take(4)
        if (servers.isEmpty()) return "Для подбора нужна рабочая VLESS-конфигурация. Прямой обход отключён."
        return doFindMasks(servers, perServer = 24)
    }

    fun nextDpi(): Boolean {
        val selected = Store.state.value.selected.filter(ServerOnlyPolicy::userRoute)
        return selected.isNotEmpty() && nextMasks(selected)
    }

    fun fixAll(then: () -> Unit) = launch("Чиню всё сам") {
        if (!online()) return@launch "Нет активной сети Wi-Fi или мобильных данных. После её появления проверю рабочие VLESS-серверы; прямой обход отключён."
        val n = runCatching { autoRescue(emptyList(), 3) { step -> progress.value = progress.value.copy(title = "Чиню: $step") } }.getOrDefault(0)
        if (n == 0) return@launch "Рабочий VLESS-сервер не найден. Прямой режим отключён; VPN не запускаю. Проверьте подписки или повторите позже."
        android.os.Handler(android.os.Looper.getMainLooper()).post(then)
        "Готово: выбрано рабочих VLESS-серверов $n. Переподключаюсь через проверенный маршрут…"
    }

    fun mbps(kbps: Int): String = if (kbps >= 10_000) "${kbps / 1000} Мбит/с" else "%.1f Мбит/с".format(java.util.Locale.US, kbps / 1000.0)

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f ГБ".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "${b shr 20} МБ"
        else -> "${b shr 10} КБ"
    }

    data class NetReport(val verdict: String = "", val details: String = "", val kind: Kind = Kind.NONE)
    enum class Kind { NONE, OPEN, DPI, WHITELIST, OFFLINE, PARTIAL }
    val netReport = MutableStateFlow(NetReport())

    fun diagnoseNetwork() = launch("Проверка маршрута VLESS") {
        val auth = Tunnel.socks ?: run {
            val report = NetReport("Сначала подключитесь к рабочему VLESS-серверу", "Проверки без сервера отключены.", Kind.NONE)
            netReport.value = report
            return@launch report.verdict
        }
        val total = 6
        step(0, total)
        val settings = Store.state.value.settings
        val results = coroutineScope {
            val control = async(Bg.io) { Tester.site(settings.testUrl, auth.port).answers }
            val youtube = async(Bg.io) { Tester.youtubeMedia(auth.port).ok == true }
            val telegram = async(Bg.io) { TelegramProbe.check(auth.port) == true }
            val whatsapp = async(Bg.io) { Tester.site("https://web.whatsapp.com/", auth.port).answers }
            val discord = async(Bg.io) { Tester.site("https://discord.com/api/v9/gateway", auth.port).answers }
            val voiceUdp = async(Bg.io) { UdpCallProbe.probe(auth.port, auth.user, auth.pass, 1500) }
            listOf(control.await(), youtube.await(), telegram.await(), whatsapp.await(), discord.await(), voiceUdp.await())
        }
        step(total, total)
        if (Tunnel.socks?.port != auth.port) return@launch "Маршрут изменился во время проверки — результат отброшен."
        val labels = listOf("базовый маршрут", "YouTube", "Telegram", "WhatsApp", "Discord")
        val details = "Сеть «${Net.key(app)}» · только через VLESS: " + labels.zip(results.take(5)).joinToString(" · ") { (name, ok) -> "$name ${if (ok) "✓" else "✗"}" } +
            " · UDP/STUN для звонков ${if (results[5]) "✓" else "не подтвердился"}"
        val servicesOk = results.take(5).all { it }
        val report = if (!results[0]) NetReport("Выбранный VLESS-маршрут не отвечает — попробуйте «Авто» или другой сервер", details, Kind.OFFLINE)
            else if (servicesOk) NetReport("YouTube, Telegram, WhatsApp и Discord отвечают через VLESS", details, Kind.OPEN)
            else NetReport("Часть проверок через VLESS не прошла — попробуйте «Авто» или другой сервер", details, Kind.PARTIAL)
        netReport.value = report
        "${report.verdict} ($details)"
    }

    fun whitelistServers() = launch("Проверка серверов подписки") {
        val loaded = doRefresh()
        val candidates = Store.state.value.servers.filter { com.vlesscardvpn.model.Subs.isWhitelist(it.source) && ServerOnlyPolicy.userRoute(it) }
        if (candidates.isEmpty()) return@launch "$loaded. VLESS-серверов в такой подписке нет."
        val working = realTest(candidates.take(150), "Проверка VLESS-серверов")
        val n = selectBest(10, candidates)
        "Через VLESS работают $working из ${minOf(candidates.size, 150)} серверов подписки; выбрано лучших: $n."
    }

    fun wipe() {
        cancel()
        Store.wipe(app)
        dpiResults.value = emptyMap(); netReport.value = NetReport()
        progress.value = Progress(message = "Все данные стёрты")
    }

    fun yn(b: Boolean?) = when (b) { true -> "да"; false -> "нет"; null -> "—" }

    const val MAX_PER_SUB = 10000
}
