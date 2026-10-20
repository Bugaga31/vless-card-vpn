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
    private val foregroundDiscovery = ForegroundDiscovery()
    @Volatile private var activeSearchReport: AutoSearchReport? = null
    private val probeGeneration = java.util.concurrent.atomic.AtomicLong()
    private var requestedSourceJob: Job? = null
    private var requestedSourceAttempt = -60000L

    fun init(context: Context) {
        app = context.applicationContext
        maybeLoadRequestedSource()
        runCatching { Brain.load(app) } // load before the first probe can initialize fresh weights
        // Preserve legacy model files as well as current weights; removal requires an explicit user wipe.
    }

    fun cancelForNetworkChange() {
        probeGeneration.incrementAndGet()
        autoGeneration.incrementAndGet()
        job?.cancel()
        progress.value = progress.value.copy(running = false, message = "Сеть изменилась — старые проверки отменены")
    }

    fun cancel() { probeGeneration.incrementAndGet(); job?.cancel(); progress.value = progress.value.copy(running = false, message = "Остановлено") }

    /** One in-flight public refresh; never block the connect button on mirror timeouts. */
    fun refreshPublicCloud() {
        if (!Report.enabled(app) || !cloudRefreshing.compareAndSet(false,true)) return
        scope.launch(Bg.io) {
            try { CloudControl.refresh(app); CloudSpeed.refresh(app) }
            finally { cloudRefreshing.set(false) }
        }
    }

    private fun launch(title: String, block: suspend () -> String) {
        if (job?.isActive == true) return
        progress.value = Progress(title, running = true)
        job = scope.launch {
            val msg = Store.busy { runCatching { withContext(Dispatchers.IO) { XrayCore.init(app) }; block() }.getOrElse { if (it is kotlinx.coroutines.CancellationException) "Остановлено" else Errors.human(it).replaceFirstChar { c -> c.uppercase() } } }
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
                    if (r.isSuccessful) r.body?.byteStream()?.use { String(BoundedInput.read(it, 2 * 1024 * 1024)) } else null
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
    /** Opens [input] three ways at once: directly (the app itself is outside the VPN), through the VPN, through the DPI bypass. */
    fun checkSite(input: String) {
        if (siteCheck.value.running) return
        val host = Settings.host(input) ?: run { siteCheck.value = SiteCheck(verdict = "Введите адрес сайта, например rutracker.org"); return }
        siteCheck.value = SiteCheck(running = true, host = host, verdict = "Проверяю $host…")
        val vpn = Tunnel.socks?.port; val dpi = Tunnel.byeDpiPort
        scope.launch(Bg.io) {
            val url = "https://$host/"
            val d = async { Tester.site(url, null) }
            val v = vpn?.let { p -> async { Tester.site(url, p) } }
            val b = dpi?.takeIf { it != vpn }?.let { p -> async { Tester.site(url, p) } }
            siteCheck.value = siteVerdict(host, d.await(), v?.await(), b?.await())
        }
    }

    private fun word(p: Tester.SiteProbe?) = when (p?.result) {
        null -> "—"; "ok" -> "открывается (${p.ms} мс)"; "timeout" -> "зависает"; "reset" -> "соединение сбрасывают"
        "dns" -> "адрес не находится"; "tls" -> "подмена/ошибка шифрования"; "denied" -> "отказ в доступе (${p.code})"; else -> "не открывается"
    }

    fun siteVerdict(host: String, direct: Tester.SiteProbe, vpn: Tester.SiteProbe?, dpi: Tester.SiteProbe?): SiteCheck {
        val details = "Напрямую: ${word(direct)} · через VPN: ${if (vpn == null) "VPN выключен" else word(vpn)}" + (dpi?.let { " · через обход DPI: ${word(it)}" } ?: "")
        val s = Store.state.value.settings
        return when {
            vpn != null && vpn.result == "denied" && direct.ok -> SiteCheck(host = host, verdict = "Через VPN сайт отказывает (не любит страну или IP сервера) — откройте напрямую", details = details,
                suggest = if (host in s.alwaysDirect) "" else "direct")
            vpn != null && direct.result == "denied" && vpn.ok -> SiteCheck(host = host, verdict = "Сайт закрыт для России, через VPN работает", details = details, good = true,
                suggest = if (host in s.alwaysVpn) "" else "vpn")
            direct.result == "denied" && (vpn == null || vpn.result == "denied") -> SiteCheck(host = host,
                verdict = "Сайт отвечает, но отказывает" + (if (vpn == null) " — подключитесь и проверьте ещё раз" else " и напрямую, и через VPN (страна сервера тоже закрыта или он не пускает проверку) — попробуйте сервер другой страны"), details = details)
            vpn == null && direct.ok -> SiteCheck(host = host, verdict = "Сайт открывается и без VPN", details = details, good = true)
            vpn == null -> SiteCheck(host = host, verdict = "Без VPN сайт не открывается — подключитесь и проверьте ещё раз", details = details)
            vpn.ok && direct.ok -> SiteCheck(host = host, verdict = "Сайт работает. Если в приложении не грузится — проблема в нём, не в сети", details = details, good = true,
                suggest = if (host in s.alwaysDirect || host in s.alwaysVpn) "" else "direct")
            vpn.ok -> SiteCheck(host = host, verdict = "Провайдер блокирует сайт, через VPN он работает", details = details, good = true,
                suggest = if (host in s.alwaysVpn) "" else "vpn")
            direct.ok -> SiteCheck(host = host, verdict = "Сайт не пускает через VPN (блокирует иностранные IP) — откройте его напрямую", details = details,
                suggest = if (host in s.alwaysDirect) "" else "direct")
            dpi?.ok == true -> SiteCheck(host = host, verdict = "Работает через обход DPI, но не через сервер — смените сервер или режим «Гибрид»", details = details)
            direct.result == "dns" && vpn.result == "dns" -> SiteCheck(host = host, verdict = "Такого сайта нет или он выключен (адрес не находится нигде)", details = details)
            else -> SiteCheck(host = host, verdict = "Сайт не открывается ни напрямую, ни через VPN — скорее всего он сам лежит", details = details)
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
        val vpn = Tunnel.socks?.port
        popular.value = Popular(running = true, summary = "Проверяю ${POPULAR.size} сервисов…")
        scope.launch(Bg.io) {
            val rows = coroutineScope {
                POPULAR.map { (title, host) -> async {
                    val d = async { Tester.site("https://$host/", null) }; val v = vpn?.let { p -> async { Tester.site("https://$host/", p) } }
                    title to siteVerdict(host, d.await(), v?.await(), null)
                } }.awaitAll()
            }
            popular.value = Popular(rows = rows, summary = popularSummary(rows, vpn != null))
        }
    }

    fun popularSummary(rows: List<Pair<String, SiteCheck>>, vpnOn: Boolean): String {
        val bad = rows.filter { !it.second.good }.map { it.first }
        return when {
            !vpnOn -> "Без VPN: открывается ${rows.count { it.second.good }} из ${rows.size}. Подключитесь, чтобы сравнить"
            bad.isEmpty() -> "Через VPN работает всё (${rows.size} из ${rows.size})"
            else -> "Не работает: " + bad.joinToString(", ") + if (bad.size > rows.size / 2) ". Похоже, сервер плохой — «Не работает? Починить»" else ""
        }
    }

    /** «Всегда через VPN» / «Всегда напрямую» (or [where] = "" to forget). Returns true when the tunnel should reconnect. */
    fun routeSite(host: String, where: String) {
        Store.update { st -> val s = st.settings
            st.copy(settings = s.copy(alwaysVpn = (s.alwaysVpn - host).let { if (where == "vpn") it + host else it },
                alwaysDirect = (s.alwaysDirect - host).let { if (where == "direct") it + host else it }))
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

    /** TCP ping of all servers, then real test of the [realLimit] fastest. */
    fun testAll(onlySelected: Boolean = false, realLimit: Int = 150) = launch("Проверка серверов") {
        val st0 = Store.state.value
        val r = doTestAll(if (onlySelected) st0.selected else st0.servers, realLimit)
        // Few work: masks for the reachable ones right away (no separate button to find)
        val st1 = Store.state.value
        val cand = st1.servers.filter { st1.state(it).tcpMs > 0 && !st1.state(it).works }.sortedBy { st1.state(it).tcpMs }.take(8)
        if (!onlySelected && st1.servers.count { st1.state(it).works } < 5 && cand.isNotEmpty()) r + ". " + doFindMasks(cand, 24, budgetMs = 120_000) else r
    }

    private suspend fun doTestAll(list: List<Server>, realLimit: Int, big: Boolean = true): String {
        if (list.isEmpty()) return "Нет серверов — добавьте ссылку или обновите подписки"
        progress.value = progress.value.copy(title = "TCP-пинг")
        val done = AtomicInteger()
        val tcp = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val batch = StateBatch()
        Tester.tcp(list, parallel = 192, timeoutMs = 2000) { s, ms ->
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
        var working = realTest(alive, "Проверка через Xray", big, attempts = 1)
        val again = Store.state.value.let { s -> alive.filter { !s.state(it).works } }
        if (again.isNotEmpty() && again.size < alive.size * 3 / 4 + 4) working += realTest(again, "Повторная проверка", big, attempts = 2)
        return "Работают: $working из ${alive.size} доступных по TCP (всего ${list.size})"
    }

    private suspend fun realTest(servers: List<Server>, title: String, big: Boolean = true, attempts: Int = 2, parallel: Int = 32, youtube: Boolean = true,
                                 stopAfter: Int = Int.MAX_VALUE, budgetMs: Long = 0, onFirst: ((Server) -> Unit)? = null, onSuccess: ((Server) -> Unit)? = null): Int {
        if (servers.isEmpty()) return 0
        progress.value = progress.value.copy(title = title, done = 0, total = servers.size)
        val net = Net.key(app)
        val underlying = Net.underlying(app)?.toString()
        val pinned = pinCertificates(servers)
        if (Net.key(app) != net || Net.underlying(app)?.toString() != underlying) return 0
        val st = Store.state.value
        val generation = probeGeneration.get()
        val chain = RouteContext.key(st.settings)
        fun validContext() = generation == probeGeneration.get() && chain == RouteContext.key(Store.state.value.settings)
        val variants = pinned.map { it to (Masks.byId(st.state(it).maskFor(net))) }
        val (own, port) = dpiForTests(variants.map { it.second })
        val done = AtomicInteger(); val ok = AtomicInteger()
        val states = StateBatch(200)
        val serviceResults = ServiceRoutes.targets.filter(ServiceRoutes::native).associate { target ->
            target.id to java.util.concurrent.ConcurrentHashMap<String, Int>()
        }
        try {
            Tester.real(variants, st.settings.testUrl, port, own.ports, batch = if (onFirst != null) ConnectPolicy.FIRST else 64, parallel = parallel, big = big, youtube = youtube, attempts = attempts, stopAfter = stopAfter, budgetMs = budgetMs, connectionCheck = st.settings.mode == Mode.AUTO && (youtube || big)) { i, p ->
                if (p == Tester.SKIPPED) return@real
                // Do not train the old profile with a result completed after Wi-Fi/cellular handoff.
                if (!validContext() || Net.key(app) != net || Net.underlying(app)?.toString() != underlying) return@real
                val s = variants[i].first
                if (youtube) ServiceRoutes.targets.filter(ServiceRoutes::native).forEach { target ->
                    ServiceRoutes.measurement(target,p)?.let { serviceResults.getValue(target.id)[s.id] = it }
                }
                if (com.vlesscardvpn.BuildConfig.DEBUG && (youtube || big)) android.util.Log.i("E2E",
                    "service-probe https=${p.works} yt=${p.ytOk} media=${p.ytReason} tg=${p.tgOk} httpsHosts=${p.httpsHosts} kbps=${p.kbps} isolated=${parallel == 1}")
                if (youtube) activeSearchReport?.record(s.id + "|" + st.state(s).maskFor(net), p)
                val measuredKbps = ServiceProbePolicy.isolatedSpeed(p, parallel)
                val speedSample = ServerChoice.acceptsSpeedSample(measuredKbps, parallel)
                val checkedAt = System.currentTimeMillis()
                val qualified = p.works && (st.settings.mode != Mode.AUTO || !youtube || AutoEligibility.connectionWorks(p))
                val firstSuccess = qualified && ok.incrementAndGet() == 1
                if (qualified) onSuccess?.invoke(s)
                // A deliberately short attempt is inconclusive when it times out; confirm with the normal budget.
                if (!p.works && budgetMs in 1 until ConnectPolicy.QUICK_BUDGET_MS) return@real
                val verdict = if (st.settings.mode == Mode.AUTO && (youtube || big)) AutoEligibility.connectionVerdict(p) else AutoEligibility.learningVerdict(p, false)
                if (verdict != null) {
                    probeMemory.record(net, s.id, st.state(s).maskFor(net), verdict, checkedAt)
                    if (!verdict) handoverPolicy.failure(net, AutoRoutePolicy.configuration(s, st.state(s), net), checkedAt)
                    runCatching { Brain.learn(net, s, st.state(s), verdict) }
                }
                states.put(s.id) {
                    if (!validContext() || Net.key(app) != net || Net.underlying(app)?.toString() != underlying) it else
                    it.copy(netEvidence = (it.netEvidence + ((net + "|" + st.state(s).maskFor(net)) to (if (verdict == null) it.evidenceFor(net) else it.evidenceFor(net).observe(verdict, p.realMs, checkedAt)))).entries.sortedByDescending { e -> e.value.at }.take(8).associate { e -> e.toPair() }, realMs = if (p.httpsChecked && p.httpsHosts < HttpsTransferProbe.REQUIRED_HOSTS) it.realMs else p.realMs, bigOk = p.bigOk ?: it.bigOk?.takeIf { old -> old }, ytOk = if (youtube) p.ytOk else it.ytOk, tgOk = if (youtube) p.tgOk else it.tgOk, checkedAt = checkedAt, kbps = if (speedSample) measuredKbps else it.kbps,
                        speedNetwork = if (speedSample) net else it.speedNetwork,
                        speedMask = if (speedSample) st.state(s).maskFor(net) else it.speedMask,
                        speedCheckedAt = if (speedSample) checkedAt else it.speedCheckedAt,
                        okCount = it.okCount + if (p.works) 1 else 0, failCount = it.failCount + if (!p.works && (!p.httpsChecked || verdict == false)) 1 else 0).let { updated ->
                            AutoEligibility.recordConnection(if (youtube) AutoEligibility.recordServices(updated, net, st.state(s).maskFor(net), checkedAt, p)
                            else updated,net,st.state(s).maskFor(net),checkedAt,p)
                        }
                }
                if (firstSuccess && onFirst != null) { states.flush(); onFirst(s) }
                step(done.incrementAndGet(), variants.size)
            }
        } finally {
            own?.close(); states.flush(); runCatching { Brain.save(app) }
            if (youtube && validContext() && Net.key(app) == net && Net.underlying(app)?.toString() == underlying) {
                val current = Store.state.value
                serviceResults.forEach { (id, results) ->
                    val valid = results.entries.filter { (serverId, _) ->
                        val server = pinned.first { it.id == serverId }
                        current.state(server).maskFor(net) == st.state(server).maskFor(net)
                    }.map { it.key to it.value }
                    if (valid.isNotEmpty()) ServiceRoutes.update(net,id,valid,android.os.SystemClock.elapsedRealtime())
                }
            }
        }
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
        val currentDpiOk = when (DpiStrategies.resolve(cfg, net0).engine) {
            DpiEngine.BYEDPI -> dpiOk
            DpiEngine.TPWS -> tpwsOk
            DpiEngine.XRAY -> true
        }
        val maskNetwork = Net.underlying(app)?.toString()
        val stats = MaskSearchPolicy.statsForNetwork(Store.state.value.maskStats, net0)
        // Learned order: masks that passed on this network (on any server) first, untested next, proven failures last.
        fun bucket(m: Mask): Int = MaskSearchPolicy.bucket(stats[m.id])
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
        val whitelisted = Whitelist.last.value?.let { it.verdict == Whitelist.Verdict.WHITELIST && System.currentTimeMillis() - it.at < 3600_000L } == true
        val order = { s: Server ->
            val ok = { m: Mask -> MaskSearchPolicy.allowed(m, s, cfg, dpiOk, tpwsOk, currentDpiOk) }
            val fresh = evoCand.filter(ok).shuffled().take(slots)
            val base = (CloudControl.maskHints(Net.family(net0)).mapNotNull { Masks.byId(it) }.filter(ok) + Masks.searchOrder(dpiOk, s, cfg.maskFamilies, cfg.maskFps) + kept.filter(ok)).distinctBy { it.id }
                .filter { (tpwsOk || !it.dpi.startsWith("TPWS#")) && it !in fresh }
                .sortedWith(compareBy<Mask>({ bucket(it) }, { Masks.cost(it) }, { -(stats[it.id]?.takeIf { s -> s.ok > 0 }?.score ?: 0.0) })).take((perServer - fresh.size).coerceAtLeast(1))
            // proven ones first, then one of every kind of trick (not a dozen variants of the same one)
            val proven = base.filter { bucket(it) == 0 }.take(3).ifEmpty { base.take(1) }
            val rest = Masks.diverse(base - proven.toSet())
            // operator whitelist on: masks that present an allowed site (SNI vk.com / ya.ru…) go right after the proven ones
            if (whitelisted) proven + rest.filter { it.sni.isNotEmpty() } + fresh + rest.filter { it.sni.isEmpty() } else proven + fresh + rest
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
        fun eff(m: Mask, p: Probe) = MaskSearchPolicy.cost(m, p)
        progress.value = progress.value.copy(title = "Подбор маскировки")
        val phases = variants.indices.groupBy { i -> val m = variants[i].second; when {
            m == null -> 0
            bucket(m) == 0 -> 0 // already proven here; no need to rediscover it from scratch
            m.viaByeDpi -> 2
            Masks.isWarp(variants[i].first) || m.hasNoise -> 3
            m.packets.isNotEmpty() || m.signature -> 1
            else -> 0
        } }.toSortedMap()
        for ((phase, indices) in phases) {
            if (System.currentTimeMillis() > deadline || pinned.all { (found[group(it)] ?: 0) >= enough }) break
            val current = indices.map { variants[it] }
            val (own, port) = dpiForTests(current.map { it.second })
            progress.value = progress.value.copy(title = "Подбор обхода: этап ${phase + 1}")
            try {
            Tester.real(current, cfg.testUrl, port, own.ports, youtube = false, batch = 64, parallel = 12, attempts = 1,
                skip = { i -> System.currentTimeMillis() > deadline || synchronized(best) { (found[group(variants[indices[i]].first)] ?: 0) >= enough } || dead(variants[indices[i]].second) }) { i, p ->
                if (Net.key(app) != net0 || Net.underlying(app)?.toString() != maskNetwork) return@real
                val (s, m) = variants[indices[i]]
                synchronized(best) {
                    val cur = best[s.id]
                    if (m != null && p !== Tester.SKIPPED) (if (p.works) kindOk else kindFail).merge(Masks.kind(m), 1, Int::plus)
                    if (p.works && m != null && (cur == null || eff(m, p) < eff(cur.first, cur.second))) best[s.id] = m to p
                    if (p.works && m != null) { good.getOrPut(s.id) { mutableListOf() } += m to eff(m, p); found.merge(group(s), 1, Int::plus) }
                    if (m != null && p !== Tester.SKIPPED) tried += Triple(s.id, m.id, p.works)
                }
                step(done.incrementAndGet(), variants.size)
            }
            } finally { own.close() }
        }
        // Double check: a mask that passed once can be a fluke (the TSPU sometimes lets the first connection through).
        // The 2 fastest of every server are probed again, now with YouTube; the one that passes again wins.
        val confirm = pinned.flatMap { s -> good[s.id].orEmpty().let { g -> (g.sortedBy { it.second }.take(2) + g.sortedWith(compareBy({ Masks.cost(it.first) }, { it.second })).take(1)) }
            .distinctBy { it.first.id }.map { s to it.first } }
        if (confirm.size > pinned.size / 2 && System.currentTimeMillis() < deadline) {
            val res = java.util.concurrent.ConcurrentHashMap<Int, Probe>()
            progress.value = progress.value.copy(title = "Двойная проверка маскировок")
            val (own2, port2) = dpiForTests(confirm.map { it.second })
            try {
                Tester.real(confirm, cfg.testUrl, port2, own2.ports, youtube = true, batch = 64, parallel = 24, attempts = 1, big = true) { i, p -> res[i] = p; step(i + 1, confirm.size) }
            } finally { own2.close() }
            pinned.forEach { s ->
                val mine = confirm.indices.filter { confirm[it].first.id == s.id }
                // passed again (with YouTube) and the fastest in real use: ping + download speed + how heavy the mask is
                val pick = mine.filter { res[it]?.works == true && res[it]?.ytOk == true }.minByOrNull { eff(confirm[it].second, res.getValue(it)) }
                    ?: mine.filter { res[it]?.works == true }.minByOrNull { eff(confirm[it].second, res.getValue(it)) }
                if (pick != null) best[s.id] = confirm[pick].second to res.getValue(pick)
                mine.forEach { i -> if (res[i]?.works == false) synchronized(best) { tried += Triple(s.id, confirm[i].second.id, false) } }
            }
        }
        if (Net.key(app) != net0 || Net.underlying(app)?.toString() != maskNetwork) return "Сеть изменилась — результаты старой маскировки отброшены"
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
        pinned.forEach { s ->
            val b = best[s.id]
            Store.setState(s.id) {
                if (b == null) it.copy(realMs = 0, checkedAt = System.currentTimeMillis())
                else it.copy(maskId = b.first.id, netMasks = it.netMasks + (net to b.first.id), realMs = b.second.realMs, bigOk = b.second.bigOk,
                    kbps = if (b.second.kbps > 0) b.second.kbps else it.kbps, speedCheckedAt = 0, ytOk = b.second.ytOk ?: it.ytOk,
                    checkedAt = System.currentTimeMillis(),
                    goodMasks = good[s.id].orEmpty().sortedBy { g -> g.second }.map { g -> g.first.id }.filter { g -> g != b.first.id }.take(8))
            }
        }
        val bred = if (evo) ", новых авто-масок: ${winIds.count { it !in keptIds }}" else ""
        return "Маскировка найдена для ${best.size} из ${pinned.size} серверов (перебрано ${variants.size} вариантов$bred, сеть «$net»)"
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
        val (own, port) = dpiForTests(variants.map { it.second })
        try {
            val r = Tester.speedMany(variants, port, own.ports)
            if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return
            Store.setStates(variants.indices.filter { r[it] > 0 }.associate { i -> variants[i].first.id to { x: com.vlesscardvpn.model.ServerState ->
                if (x.maskFor(net) != st.state(variants[i].first).maskFor(net)) x
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
        val st = Store.state.value; val net = Net.key(app)
        val heavy = st.selected.filter { s -> Masks.byId(st.state(s).maskFor(net))?.let { Masks.cost(it) >= 2 } == true }
        if (heavy.isEmpty()) return
        val variants = heavy.flatMap { s -> Masks.searchOrder(false, s).filter { Masks.cost(it) == 0 }.take(if (wifi) 10 else 5).map { s to it } }
        val res = java.util.concurrent.ConcurrentHashMap<Int, Probe>()
        val pinned = pinCertificates(heavy).associateBy { it.id }
        val v2 = variants.map { (s, m) -> (pinned[s.id] ?: s) to m }
        Tester.real(v2, st.settings.testUrl, null, emptyMap(), youtube = true, batch = 64, parallel = 24, attempts = 1, big = true) { i, p -> res[i] = p }
        heavy.forEach { s ->
            val cur = Store.state.value.state(s)
            val win = v2.indices.filter { v2[it].first.id == s.id && res[it]?.works == true && res[it]?.ytOk != false }.minByOrNull { res.getValue(it).realMs } ?: return@forEach
            val m = v2[win].second!!; val p = res.getValue(win)
            if (cur.kbps > 0 && p.kbps in 1 until cur.kbps) return@forEach // the heavy one is still faster here: keep it
            Store.setState(s.id) { it.copy(maskId = m.id, netMasks = it.netMasks + (net to m.id), realMs = if (p.httpsChecked && p.httpsHosts < HttpsTransferProbe.REQUIRED_HOSTS) it.realMs else p.realMs, bigOk = p.bigOk, kbps = p.kbps, speedNetwork = net, speedMask = m.id, speedCheckedAt = 0, checkedAt = System.currentTimeMillis()) }
            Store.recordMasks(net, listOf(m.id to true))
        }
    }

    /**
     * «Умный YouTube» (Авто): on this network, is YouTube faster through the DPI bypass straight to Google than through
     * the servers? Measured once per network (two rounds, the better of each), remembered in [Settings.ytDpi]; the bypass
     * must win clearly (×1.3 + 0.5 Мбит/с), otherwise the servers stay. True = a reconnect is needed to switch.
     */
    suspend fun decideYoutube(): Boolean {
        val st = Store.state.value; val s = st.settings; val net = Net.key(app)
        if (s.mode != com.vlesscardvpn.model.Mode.AUTO || !s.smartYoutube || s.services.isNotEmpty() || s.proxyOnly) return false
        if (Tunnel.ytViaDpi) return false
        val handle = Net.underlying(app)?.toString()
        val prefs = app.getSharedPreferences("youtube_route",0)
        val decisionKey = Net.fingerprint("youtube-v3-media",net)
        val last = prefs.getLong(decisionKey,0)
        val stamp = System.currentTimeMillis()
        if (last > 0 && stamp >= last && stamp-last < 10*60000L) return false
        if (s.dpiRemembered[net] == null && s.dpiRemembered[Net.family(net)] == null) {
            if (!PhonePower.refresh(app).allowed) return false
            // Optional search runs after connection, never on the one-button critical path.
            prefs.edit().putLong(decisionKey, stamp).apply()
            doFindDpi()
            if (Net.key(app) != net || Net.underlying(app)?.toString() != handle) return false
        }
        val vpn = Tunnel.socks?.port ?: return false
        val set = DpiSet(app)
        val (srv, dpi) = try {
            set.start(Store.state.value.settings, net, true, emptySet(), com.vlesscardvpn.BuildConfig.DEBUG)
            val dp = set.currentPort ?: return false
            Tester.compareYoutubeMedia(vpn, dp)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { return false } finally { set.close() }
        if (Net.key(app) != net || Net.underlying(app)?.toString() != handle || Tunnel.socks?.port != vpn) return false
        val use = ytDpiWins(srv, dpi)
        // The old HTML-based decision is not reusable as video evidence.
        app.getSharedPreferences(YouTubeMediaProbe.PROOF_PREFS, 0).edit()
            .putLong(Net.fingerprint("media", net), if (use) System.currentTimeMillis() else 0).apply()
        prefs.edit().putLong(decisionKey,System.currentTimeMillis()).apply()
        android.util.Log.i("E2E", "smart youtube net=$net server=$srv dpi=$dpi use=$use")
        Store.update { a -> a.copy(settings = a.settings.copy(ytDpi = a.settings.ytDpi + (net to use))) }
        return use
    }

    fun ytDpiWins(serverKbps: Int, dpiKbps: Int) = dpiKbps > 0 && dpiKbps > serverKbps * 1.3 + 500

    /**
     * «Ускорь YouTube» (Помощник): Smart YouTube + phone speed-up on, the network's YouTube decision measured again,
     * heavy masks replaced by light ones, real download speed of the best 5, the fastest selected; reconnects if on.
     */
    fun boostYoutube() = launch("Ускоряю YouTube") {
        val net = Net.key(app)
        Store.update { it.copy(settings = it.settings.copy(smartYoutube = true, turbo = true, blockQuic = true, ytDpi = it.settings.ytDpi - net)) }
        progress.value = progress.value.copy(title = "Ускоряю YouTube: облегчаю маски")
        lightenMasks(true)
        // servers the cloud saw download fast that this phone has not got working yet: a real test here first
        val s0 = Store.state.value
        val fresh = runCatching { CloudSpeed.refresh(app); CloudSpeed.pick(s0.servers.filter { !s0.state(it).works }, emptyList(), n = 6) }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) realTest(fresh, "Ускоряю: проверяю ${fresh.size} быстрых по облаку", big = true, attempts = 1)
        val s1 = Store.state.value
        val top = (s1.servers.filter { s1.state(it).works }.sortedBy { s1.state(it).score }.take(6) +
            fresh.filter { s1.state(it).works }).distinctBy { it.id }
        if (top.isEmpty()) return@launch "Рабочих серверов нет — сначала «Проверить серверы»."
        progress.value = progress.value.copy(title = "Ускоряю YouTube: меряю скорость")
        realSpeed(top)
        val s2 = Store.state.value
        selectBest(10, close = true, among = top.filter { s2.state(it).works })
        val viaDpi = runCatching { decideYoutube() }.getOrDefault(false)
        val connected = Tunnel.socks != null
        if (connected) withContext(Dispatchers.Main) { com.vlesscardvpn.vpn.TunnelService.start(app) }
        val best = Store.state.value.let { s -> s.selected.maxOfOrNull { s.state(it).kbps } ?: 0 }
        "YouTube: " + (if (best > 0) "лучший сервер качает ${"%.1f".format(best / 1000.0)} Мбит/с" else "скорость не измерилась") +
            (if (viaDpi) ", а напрямую через обход DPI ещё быстрее — YouTube пойдёт им" else "") +
            ". Включены «Умный YouTube» и ускорение телефона" + if (connected) ", переподключаюсь." else ". Подключитесь — всё применится."
    }

    /** «Облегчи маску» (Помощник). */
    fun lighten() = launch("Облегчаю маски") {
        val before = Store.state.value.let { s -> s.selected.count { Masks.byId(s.state(it).maskFor(Net.key(app)))?.let { m -> Masks.cost(m) >= 2 } == true } }
        if (before == 0) return@launch "Тяжёлых масок у выбранных серверов нет — уже самые быстрые."
        lightenMasks(true)
        val after = Store.state.value.let { s -> s.selected.count { Masks.byId(s.state(it).maskFor(Net.key(app)))?.let { m -> Masks.cost(m) >= 2 } == true } }
        if (after < before && Tunnel.socks != null) withContext(Dispatchers.Main) { com.vlesscardvpn.vpn.TunnelService.start(app) }
        "Облегчено масок: ${before - after} из $before" + if (after < before && Tunnel.socks != null) " — переподключаюсь." else if (after == before) " (лёгкие маски здесь не прошли — тяжёлые оставлены, иначе не будет работать)." else "."
    }

    private var catalogAt = -300000L
    private suspend fun catalogWave() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - catalogAt < 30000 || !PhonePower.refresh(app).allowLight || progress.value.running) return
        catalogAt = now
        val st = Store.state.value
        val available = preferred(st,st.servers.filter { !st.state(it).selected })
        val affinity = SourceAffinity.order(available,st.servers,{st.state(it)},Net.key(app),System.currentTimeMillis()) { CloudControl.sources(it) }
        val cloud = (CloudSpeed.pick(available,emptyList(),n=4) + affinity.take(4) + CloudControl.ranked(available,8)).distinctBy { it.id }.take(16)
        val wave = PublicCloudQueue.merge(available.sortedBy { st.state(it).checkedAt },cloud,available,ConnectPolicy.BACKGROUND,slots=8)
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
            realTest(test,"Фон: Telegram и YouTube, очередь ${available.size}",big=false,youtube=true,
                attempts=1,parallel=ServiceProbePolicy.PARALLEL,budgetMs=ServiceProbePolicy.SERVICE_MS)
            checked=test.size
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
        if (now - serviceAt < (if (serviceCursor < ServiceRoutes.targets.size) 30000L else 60000L) || progress.value.running || !PhonePower.refresh(app).allowLight) return false
        val st = Store.state.value; val selected = st.selected
        if (selected.isEmpty()) return false
        serviceAt = now
        val target = ServiceRoutes.targets[serviceCursor++ % ServiceRoutes.targets.size]
        if (ServiceRoutes.native(target) && st.settings.services.isNotEmpty() && target.id !in st.settings.services) return false
        val eligible = selected.filter { !target.ai || Countries.of(it.name) !in AppWatch.AI_BAD }.take(4)
        if (eligible.isEmpty()) return false
        val net = Net.key(app); val handle = Net.underlying(app)?.toString()
        val results = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val variants = eligible.map { it to Masks.byId(st.state(it).maskFor(net)) }
        val (own, port) = dpiForTests(variants.map { it.second })
        try {
            Tester.real(variants, if (ServiceRoutes.native(target)) st.settings.testUrl else target.url,
                port, own.ports, parallel = 2, attempts = 1, big = false, youtube = ServiceRoutes.native(target),
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
    private val handoverPolicy = HandoverPolicy()
    @Volatile var lastOptimizeSwitched = false

    suspend fun optimize(): Boolean {
        lastOptimizeSwitched = false
        val lightPlan = PhonePower.refresh(app)
        if (!lightPlan.allowLight) return false
        var st = Store.state.value
        if ((!st.settings.autoOptimize && st.settings.mode != Mode.AUTO) || job?.isActive == true) return false
        val originalNet = Net.key(app)
        val originalSelection = st.selected.map { AutoRoutePolicy.configuration(it,st.state(it),originalNet) }.toSet()
        if (st.settings.mode == com.vlesscardvpn.model.Mode.BYEDPI) return false
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
            if (PhonePower.refresh(app).allowed) ytSwitch = runCatching { decideYoutube() }.getOrDefault(false)
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
            realTest(cand, "Фоновая проверка", big = wifi && !throughputMode && PhonePower.refresh(app).allowed, youtube = st.settings.mode == Mode.AUTO,
                attempts = 1, parallel = ServiceProbePolicy.PARALLEL, budgetMs = ServiceProbePolicy.SERVICE_MS)
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
        val rankNow = System.currentTimeMillis()
        val automatic = now.settings.mode == Mode.AUTO
        fun admitted(server: Server) = now.state(server).works &&
            (!automatic || AutoEligibility.connectionPool(listOf(server),{now.state(it)},sampleNet,rankNow).isNotEmpty())
        val selOk = sel.filter(::admitted)
        val best = automaticOrder(now, cand.filter(::admitted))
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
        val failedSelection = selOk.size < sel.size || selOk.isEmpty() || selBroken
        val currentRoute = currentFastest?.let { AutoRoutePolicy.configuration(it, now.state(it), net) }.orEmpty()
        val candidateRoute = AutoRoutePolicy.configuration(best.first(), now.state(best.first()), net)
        val stableGain = if (automatic) handoverPolicy.allow(net, currentRoute, candidateRoute, stamp,
            now.state(best.first()).speedCheckedAt, qualityGain, failedSelection) else qualityGain
        val switch = failedSelection || stableGain
        val pick = if (selBroken) best.filter { now.state(it).bigOk == true } else best
        // Filling unused reserve slots is allowed; an already-full healthy pool is not reselected every pass.
        val fillReserves = automatic && selOk.size < AutoEligibility.TOP && best.size > selOk.size
        if (switch || fillReserves) selectBest(AutoEligibility.TOP, close = true, among = pick)
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
    fun setupWarp(accounts: Int = 3, onlyKey: String? = null) = launch("WARP") {
        val msg = doSetupWarp(if (onlyKey != null) 1 else accounts, onlyKey)
        // Ready = connect right away (or reconnect onto WARP): no guessing whether WARP is on.
        val st = Store.state.value
        val warpOk = st.selected.any { Masks.isWarp(it) && st.state(it).works }
        if (warpOk && (st.settings.proxyOnly || android.net.VpnService.prepare(app) == null)) {
            withContext(Dispatchers.Main) { com.vlesscardvpn.vpn.TunnelService.start(app) }
            msg.replace("Нажмите «Подключить».", "Подключаюсь через WARP — на главном экране будет «WARP: включён ✓».")
        } else msg
    }

    /** Remembers Cloudflare's answers about WARP+ keys («Ключи WARP+» shows them; used-up keys go last next time). */
    private fun rememberKeys(results: Map<String, String>) {
        if (results.isNotEmpty()) Store.update { st -> st.copy(settings = st.settings.copy(warpKeyStatus = st.settings.warpKeyStatus + results)) }
    }

    /** [onlyKey]: «Подключить с этим ключом» in «Ключи WARP+» — one account, just this key. */
    suspend fun doSetupWarp(accounts: Int = 3, onlyKey: String? = null): String {
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
                r.onSuccess { accs += it }.onFailure { err = Errors.human(it) }
            }
        }
        // WARP+: bind a key to every account (own keys first, then the built-in public ones).
        val cfg = Store.state.value.settings
        val own = WarpKeys.parse(cfg.warpKeys)
        val order = if (onlyKey != null) mutableListOf(onlyKey)
            else WarpKeys.order(own, cfg.warpBuiltinKeys, cfg.warpKeyOff, cfg.warpKeyStatus).toMutableList()
        if (order.isNotEmpty()) {
            progress.value = progress.value.copy(title = "WARP: ключ WARP+")
            val seen = HashMap<String, String>()
            withContext(Dispatchers.IO) {
                accs.indices.forEach { i ->
                    val (key, e) = Warp.upgrade(accs[i], order, maxTries = if (onlyKey != null) 1 else 12) { k, err ->
                        WarpKeys.classify(err)?.let { seen[k] = WarpKeys.mark(it) }
                        // used up / invalid: don't retry it for the next account
                        if (err != null && WarpKeys.classify(err) != null) order.remove(k)
                    }
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
        Store.replaceSource(Warp.SOURCE, list)
        // one working endpoint per account is enough (only one tunnel per key can run at a time anyway)
        // 10 most likely masks, 2.5 min at most: an account whose endpoints are all dead must not stall the search
        val res = doFindMasks(list, perServer = 10, enough = 1, group = { it.secret }, budgetMs = 150_000)
        val st = Store.state.value
        val ok = list.filter { st.state(it).works }
        android.util.Log.i("E2E", "warp servers=${list.size} working=${ok.size} masks=${ok.map { st.state(it).maskId }}")
        // one endpoint per account: two tunnels with the same key at once would roam and stall
        if (ok.isNotEmpty()) selectBest(3, ok.sortedBy { st.state(it).score }.distinctBy { it.secret })
        return if (ok.isEmpty()) "WARP: аккаунтов ${accs.size}, но ни одна точка входа не ответила ($res). WireGuard в этой сети, похоже, режут."
        else "WARP готов: рабочих точек входа ${ok.size} (по одной на аккаунт, всего ${list.size}), выбраны. " +
            (if (plus > 0) "WARP+: $plus из ${accs.size} аккаунтов. " else if (plusErr.isNotEmpty()) "WARP+ не подключился ($plusErr) — обычный WARP. " else "") +
            "Нажмите «Подключить»."
    }

    // ---------- Auto mode ----------
    /**
     * «Авто»: fresh subscriptions → test → masks for the best candidates → DPI search when no server works →
     * a verified primary (or an explicitly chosen balancer pool). Then [onReady] connects (servers, or ByeDPI if none passed).
     */
    private val autoGeneration = AtomicInteger()
    fun autoConnect(onReady: () -> Unit) {
        ServiceHealth.event(ServiceHealth.EventKind.SEARCH)
        val ticket = autoGeneration.incrementAndGet()
        val initialNetwork = Net.key(app) to Net.underlying(app)?.toString()
        val ready = {
            if (autoGeneration.get() == ticket) {
                if (initialNetwork == (Net.key(app) to Net.underlying(app)?.toString())) onReady()
                else { selectionNetworkChanged(); Unit }
            }
        }
        runCatching { Report.prime(app); CloudSpeed.loadCached(app) }
        refreshPublicCloud()
        val st = Store.state.value
        val working = preferred(st, AutoEligibility.connectionPool(st.servers.filter { st.state(it).works },
            { st.state(it) }, Net.key(app), System.currentTimeMillis()))
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
            val power = PhonePower.refresh(app)
            val comparisonPool = preferred(st, working)
            val needsComparison = !GameSession.active() && power.allowLight && !power.deferTraffic && comparisonPool.isNotEmpty() &&
                (comparisonPool.minWithOrNull(ServerChoice.downloadOrder({ st.state(it) },net,now))?.let {
                    AutoRoutePolicy.needsFaster(st.state(it),net,now) } == true || comparisonPool.any {
                    ServerChoice.measuredSpeed(st.state(it),net,now)==0 && ServerChoice.candidateScore(st.state(it),net,now) <
                        (comparisonPool.filter { s -> ServerChoice.measuredSpeed(st.state(s),net,now)>0 }.minOfOrNull { s -> ServerChoice.qualityScore(st.state(s),net,now) } ?: Int.MAX_VALUE) * .8
                })
            if (needsComparison && job?.isActive != true) {
                if (Tunnel.status.value.state != Tunnel.State.CONNECTED) Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING,
                    "Сравниваю маршруты…",check="Проверяю задержку и короткое скачивание; повторное нажатие отменяет поиск")
                launch("Авто: сравниваю скорость") { preflight(ready) }
                return
            }
            if (!stale || job?.isActive == true) {
                if (working.none { now - st.state(it).checkedAt < 6 * 3600_000L }) deepPending = true
                selectBest(10, close = true, among = working)
                ready(); return
            }
            // A cached primary can start while revalidation runs; do not distribute traffic over slow reserves.
            // A changed set requires reconnecting the core; existing TCP sessions may restart.
            selectBest(8, close = false, among = working)
            ready()
            launch("Авто: быстрая проверка") { preflight(ready, connected = true) }
            return
        }
        Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Готовлю серверы…", check = "Проверяю Telegram и видео YouTube; одного пинга недостаточно")
        if (busy?.isActive == true) cancel()
        autoPrepare(ready)
    }

    /** «Подключение…» tapped again while Auto is still preparing: stop preparing. */
    fun cancelPrepare() {
        autoGeneration.incrementAndGet()
        if (progress.value.title.startsWith("Авто")) cancel()
        if (Tunnel.status.value.state == Tunnel.State.CONNECTING && Tunnel.socks == null) Tunnel.status.value = Tunnel.Status()
    }

    /** Is there internet at all outside the VPN (this app is excluded from it)? Russian and foreign hosts. */
    fun online(): Boolean = listOf("ya.ru" to 443, "1.1.1.1" to 443, "vk.com" to 443).any { (h, p) -> Tester.tcpOne(h, p, 2500) > 0 }

    /**
     * Auto mode, the connection stopped working: find something that works instead of giving up.
     * level 1 — other servers that passed; 2 — mask search (with mask evolution) for the reachable ones;
     * 3 — fresh subscriptions + full re-test + WARP. Returns the number of servers selected (0 = nothing found).
     */
    suspend fun autoRescue(failed: Collection<Server>, level: Int, onStep: (String) -> Unit = {}): Int {
        val now = System.currentTimeMillis()
        val ids = failed.map { it.id }.toHashSet()
        if (ids.isNotEmpty()) Store.setStates(failed.associate { s -> s.id to { x: com.vlesscardvpn.model.ServerState -> x.copy(realMs = 0, checkedAt = now) } })
        fun working() = Store.state.value.let { st -> st.servers.filter { it.id !in ids && st.state(it).works } }
        // re-check the spares first (2–4 s, outside the tunnel): old «works» marks must not send us to a dead server
        working().let { w -> Store.state.value.let { st -> runCatching { Brain.load(app) }; Brain.order(Net.key(app), preferred(st, w)) { st.state(it) }.take(12) } }.takeIf { it.isNotEmpty() }?.let {
            onStep("проверяю запасные серверы"); realTest(it, "Авто: проверяю запасные", big = false, attempts = 1)
        }
        working().takeIf { it.isNotEmpty() }?.let { return selectBest(10, close = true, among = it) }
        if (level < 2) return 0
        onStep("подбираю маскировку для доступных серверов")
        val st = Store.state.value
        val cand = (failed + st.servers.filter { st.state(it).tcpMs > 0 && it.id !in ids }.sortedBy { st.state(it).tcpMs }.take(10)).distinctBy { it.id }
        if (cand.isNotEmpty()) doFindMasks(cand, 24, budgetMs = 120_000)
        Store.state.value.let { s2 -> s2.servers.filter { s2.state(it).works } }.takeIf { it.isNotEmpty() }?.let { return selectBest(10, close = true, among = it) }
        // dead servers behind Cloudflare: the same server on another (unblocked) edge address
        (failed.filter { Revive.cdnLike(it) } + deadCdn()).distinctBy { it.id }.takeIf { it.isNotEmpty() }?.let { d ->
            onStep("оживляю серверы за Cloudflare"); if (doRevive(d, 6) > 0) return selectBest(10, close = true)
        }
        if (level < 3) return 0
        onStep("обновляю подписки и перепроверяю все серверы")
        if (now - st.settings.lastSubRefresh > 3600_000L || st.servers.size < 20) doRefresh()
        doTestAll(Store.state.value.servers, 80)
        Store.state.value.let { s3 -> s3.servers.filter { s3.state(it).works } }.takeIf { it.isNotEmpty() }?.let { return selectBest(10, close = true, among = it) }
        val s4 = Store.state.value
        val c2 = s4.servers.filter { s4.state(it).tcpMs > 0 }.sortedBy { s4.state(it).tcpMs }.take(10)
        if (c2.isNotEmpty()) doFindMasks(c2, 24, budgetMs = 120_000)
        if (Store.state.value.servers.none { Masks.isWarp(it) && Store.state.value.state(it).works }) { onStep("настраиваю WARP"); runCatching { doSetupWarp(2, null) } }
        return Store.state.value.let { s5 -> s5.servers.filter { s5.state(it).works } }.let { if (it.isEmpty()) 0 else selectBest(10, close = true, among = it) }
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
    suspend fun quickRecheck(): Boolean {
        val net = Net.key(app); val handle = Net.underlying(app)?.toString()
        Store.update { state -> state.copy(states = state.states.mapValues { (_, value) -> value.forAutomaticNetwork(net) }) }
        fun valid() = Net.key(app) == net && Net.underlying(app)?.toString() == handle
        val st = Store.state.value
        val local = st.settings.netServers[net].orEmpty().toSet()
        val remembered = st.servers.filter { it.id in local }
        val ranked = preferred(st, st.servers).sortedWith(compareByDescending<Server> {
            Brain.p(net, it, st.state(it))
        }.thenByDescending { CloudSpeed.prior(it) ?: .5 })
        val candidates = ServerChoice.diverse(remembered + st.selected + CloudSpeed.pick(ranked, emptyList(), n = 2) + ranked, 8)
        if (candidates.isEmpty()) return false
        val passed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val prev = progress.value
        try {
            kotlinx.coroutines.withTimeoutOrNull(ServiceProbePolicy.FINAL_MS) {
                realTest(candidates.take(ServiceProbePolicy.FINALISTS), "Новая сеть: Telegram и видео YouTube", big = false, youtube = true,
                    attempts = 1, parallel = ServiceProbePolicy.PARALLEL, budgetMs = ServiceProbePolicy.SERVICE_MS, onSuccess = { passed.add(it.id) })
            }
            if (!valid()) return false
            val ok = candidates.filter { it.id in passed }
            if (ok.isEmpty()) { deepPending = true; return false }
            val winner = AutoSelection.compare(ok, { Store.state.value.state(it) }, net,
                now = { System.currentTimeMillis() }, elapsed = { android.os.SystemClock.elapsedRealtime() },
                validNetwork = { valid() }, gaming = GameSession.active(),
                hint = { maxOf(CloudControl.speed(it),CloudSpeed.speedOf(Report.serverKey(it))) },
                onInconclusive = { server, at -> expireUnreproducedSpeed(server,net,at) },
                allowDownload = { PhonePower.refresh(app).let { it.allowLight && !it.deferTraffic } }) { server, budget ->
                realTest(listOf(server), "Новая сеть: сравниваю скорость", big = true, youtube = false,
                    attempts = 1, parallel = 1, budgetMs = budget)
            } ?: return false
            if (!valid()) return false
            selectBest(10, close = false, among = ok); deepPending = true
            return true
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
        realTest(cand.take(12), "Авто: проверяю серверы", big = false, attempts = 1, youtube = false, budgetMs = ConnectPolicy.QUICK_BUDGET_MS,onSuccess={ passed.add(it.id) })
        if (cand.size > 12 && passed.size < AutoEligibility.TOP) realTest(cand.drop(12).take(12), "Авто: проверяю серверы", big = false, attempts = 1, youtube = false, budgetMs = ConnectPolicy.QUICK_BUDGET_MS,onSuccess={ passed.add(it.id) })
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
            val compared = chooseCompared(ok,null,extra) ?: run {
                if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return selectionNetworkChanged()
                return doAutoPrepare(onReady)
            }
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
        if (!connected) Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Готовлю серверы…", check = "Прежние серверы здесь не работают: ищу другие, обычно 10–20 секунд")
        return doAutoPrepare(onReady)
    }

    private fun autoPrepare(onReady: () -> Unit) = launch("Авто-настройка") { doAutoPrepare(onReady) }

    private suspend fun doAutoPrepare(onReady: () -> Unit): String {
        val report = AutoSearchReport()
        val handoff = AutoSearchHandoff()
        activeSearchReport = report
        try {
            val result = kotlinx.coroutines.withTimeoutOrNull(ServiceProbePolicy.FOREGROUND_MS) {
                doAutoPrepareWithinBudget { if (handoff.claim()) onReady() }
            }
            if (result != null) return result
            if (!handoff.expire()) return "Маршрут выбран — проверяю фактическую цепочку"
            val message = "Бюджет поиска исчерпан. " + report.summary() + " Можно повторить или выбрать сервер вручную."
            Tunnel.status.value = Tunnel.Status(Tunnel.State.ERROR, "Авто: подтверждённый маршрут не найден", check = message)
            return message
        } finally { handoff.expire(); if (activeSearchReport === report) activeSearchReport = null }
    }

    private suspend fun doAutoPrepareWithinBudget(onReady: () -> Unit): String {
        val searchScope = CoroutineScope(kotlinx.coroutines.currentCoroutineContext())
        val now = System.currentTimeMillis()
        val st0 = Store.state.value
        val notes = mutableListOf<String>()
        val started = ConnectPolicy.StartGate()
        val attempted = mutableSetOf<String>()
        val firstReady: (Server) -> Unit = { server ->
            val snapshot = Store.state.value
            if (AutoEligibility.connectionPool(listOf(server), { snapshot.state(it) }, Net.key(app), System.currentTimeMillis()).isNotEmpty() && started.claim()) {
                deepPending = true
                selectBest(10, close = false, among = listOf(server) + Store.state.value.let { a -> a.servers.filter { a.state(it).works } })
                searchScope.launch(Dispatchers.Main) { onReady() }
            }
        }
        fun fresh() = Store.state.value.let { st -> preferred(st, AutoEligibility.connectionPool(
            st.servers.filter { st.state(it).works }, { st.state(it) }, Net.key(app), System.currentTimeMillis())) }
        // Existing catalog first, even when subscription timestamps are old. Cloud cache was applied before this.
        if (st0.servers.isNotEmpty() && fresh().isEmpty()) notes += quickTest(st0.servers, firstReady, attempted)
        if (started.started) return "Передача HTTPS проверена — подключаюсь; сервисы показаны отдельно"
        if (st0.servers.isEmpty() || fresh().isEmpty() && st0.settings.autoUpdateSubs && now - st0.settings.lastSubRefresh > 12 * 3600_000L) {
            progress.value = progress.value.copy(title = "Авто: загружаю первые серверы")
            notes += doRefresh(firstReady = { list -> quickTest(list, firstReady, attempted) })
        }
        if (started.started) return "VPN подключается; подписки дозагружены в фоне"
        if (fresh().isEmpty() && Store.state.value.servers.isNotEmpty()) {
            notes += quickTest(Store.state.value.servers, firstReady, attempted)
            if (started.started) return "Передача HTTPS проверена — подключаюсь; сервисы показаны отдельно"
            val st = Store.state.value
            realTest(st.servers.filter { st.state(it).tcpMs > 0 && st.state(it).realMs < 0 && it.id !in attempted }.sortedBy { st.state(it).tcpMs }.take(ConnectPolicy.NEXT),
                "Авто: запасные маршруты для Telegram и YouTube", big = false, youtube = true, attempts = 1, parallel = ServiceProbePolicy.PARALLEL, stopAfter = Int.MAX_VALUE, budgetMs = ServiceProbePolicy.SERVICE_MS, onFirst = firstReady)
            if (started.started) return "Запасной маршрут найден — подключаюсь"
            deepPending = true
        }
        if (fresh().isEmpty()) {
            val st = Store.state.value
            val candidates = ServerChoice.diverse(st.servers.filter { st.state(it).tcpMs > 0 && AutoEligibility.serviceRank(st.state(it),Net.key(app),System.currentTimeMillis()) != 0 }.sortedWith(compareByDescending<Server> { CloudSpeed.prior(it) ?: .0 }.thenBy { st.state(it).tcpMs }), 3)
            progress.value = progress.value.copy(title = "Авто: подбираю маскировку")
            if (candidates.isNotEmpty()) notes += doFindMasks(candidates, 24, enough = 1, budgetMs = 60_000)
        }
        val working = fresh()
        if (working.isEmpty()) {
            val st = Store.state.value.settings
            if (Net.key(app).let { k -> st.dpiRemembered[k] ?: st.dpiRemembered[Net.family(k)] } == null) { progress.value = progress.value.copy(title = "Авто: подбираю обход DPI"); notes += doFindDpi() }
        }
        // A mask found only by connectivity/ping still needs an isolated throughput comparison.
        val compared = if (working.isNotEmpty()) chooseCompared(working,null) else null
        if (working.isNotEmpty() && compared == null) {
            val message = "Пригодная скорость маршрута не подтверждена. " +
                (activeSearchReport?.summary() ?: "") + " Повторите поиск или выберите сервер вручную."
            Tunnel.status.value = Tunnel.Status(Tunnel.State.ERROR, "Auto: замер скорости не завершён", check=message)
            return message
        }
        val primary = Store.state.value.settings.let { ServerChoice.automaticPrimary(
            it.mode == Mode.AUTO,it.balance == com.vlesscardvpn.model.Balance.LEAST_PING,GameSession.active()) }
        val n = selectBest(10, close = true, among = working)
        val dpiCandidate = Store.state.value.settings.let { settings ->
            settings.dpiRemembered[Net.key(app)] ?: settings.dpiRemembered[Net.family(Net.key(app))]
        } != null
        if (n == 0 && !dpiCandidate) {
            val message = activeSearchReport?.summary() ?: "Нет маршрута с подтверждением Telegram MTProto и YouTube-медиа"
            Tunnel.status.value = Tunnel.Status(Tunnel.State.ERROR, "Авто: маршрут не подтверждён", check = message)
            return message
        }
        withContext(Dispatchers.Main) { onReady() }
        return if (n > 0) "Авто: выбрано $n лучших серверов — подключаюсь" else "Авто: проверяю Telegram и видео через сохранённую стратегию обхода DPI"
    }

    /** Cached cloud/network candidates first; no whole-catalog TCP scan or video downloads before first connect. */
    suspend fun quickTest(list: List<Server>, onFirst: ((Server) -> Unit)? = null, attempted: MutableSet<String> = mutableSetOf()): String {
        CloudSpeed.loadCached(app)
        val st = Store.state.value
        val untried = list.filter { it.id !in attempted }
        if (untried.isEmpty()) return "Эти маршруты уже проверены в текущем поиске"
        val net = Net.key(app); val network = Net.underlying(app)?.toString()
        val eligible = preferred(st, untried).filterNot { CloudSpeed.blocked(it) }.ifEmpty { preferred(st, untried) }
        val affinity = SourceAffinity.order(eligible,Store.state.value.servers,{Store.state.value.state(it)},net,System.currentTimeMillis()) { CloudControl.sources(it) }
        val cloud = (CloudSpeed.pick(eligible, emptyList(), n = 4) + affinity.take(4) + CloudControl.ranked(eligible,8)).distinctBy { it.id }.take(ConnectPolicy.FIRST)
        val remembered = (st.settings.netServers[net] ?: st.settings.netServers[Net.family(net)]).orEmpty().toSet()
        val time = System.currentTimeMillis()
        val gaming = GameSession.active()
        val speedRank = eligible.sortedWith(ServerChoice.downloadOrder({ st.state(it) },net,time))
            .mapIndexed { index,server -> server.id to index }.toMap()
        val learned = eligible.sortedWith(compareByDescending<Server> {
            if (gaming) -ServerChoice.latencyScore(st.state(it).evidenceFor(net), st.state(it).realMs)
            else if (ServerChoice.measuredSpeed(st.state(it),net,time) > 0) 2.0 - (speedRank[it.id] ?: eligible.size).toDouble() / (eligible.size + 1)
            else ServerChoice.blended(CloudSpeed.prior(it), st.state(it).evidenceFor(net), time) }
            .thenByDescending { Brain.p(net, it, st.state(it)) }).mapIndexed { i, server -> server.id to i }.toMap()
        val first = ConnectPolicy.candidates(eligible, cloud, remembered, cooling = { server ->
            val e = st.state(server).evidenceFor(net)
            probeMemory.cooling(net, server.id, st.state(server).maskFor(net), time) ||
                (e.streak > 0 && time >= e.at && time - e.at < ProbeMemory.cooldownMs(e.streak))
        }) { s -> learned[s.id] ?: Int.MAX_VALUE }
        val cachedGood = first.filter { st.state(it).evidenceFor(net).let { e -> e.successes > 0 && e.streak == 0 && time >= e.at && time - e.at < 30_000 && AutoEligibility.serviceRank(st.state(it),net,time) == 0 } }.map { it.id }.toSet()
        val reachable = Tester.fastFilter(first.filter { it.id !in cachedGood })
        val available = first.filter { (reachable[it.id] ?: -1) != 0 }
        val ordered = available.sortedBy { if (it.id in cachedGood || (reachable[it.id] ?: -1) > 0) 0 else 1 }
        // Check up to eight real routes; parallelism stays bounded and downloads remain sequential.
        val shortlist = ServerChoice.diverse(ordered, AutoEligibility.TOP)
        val passed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val fastOk = realTest(shortlist, "Авто: проверяю до восьми маршрутов", big = false, youtube = false,
            attempts = 1, parallel = 4, budgetMs = ConnectPolicy.QUICK_BUDGET_MS,onSuccess={ passed.add(it.id) })
        if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return selectionNetworkChanged()
        if (fastOk > 0 && chooseCompared(shortlist.filter { it.id in passed },onFirst,
                eligible.filter { s -> shortlist.none { it.id == s.id } }) != null) {
            deepPending = true; return "Передача HTTPS проверена; выбран маршрут с измеренной скоростью. Сервисы — отдельно"
        }
        attempted.addAll(shortlist.map { it.id });passed.clear()
        val additional = ordered.filter { it.id !in attempted }
        attempted.addAll(additional.map { it.id })
        val ok = realTest(additional, "Авто: первые ${first.size} маршрутов (облако: ${cloud.size})", big = false,
            youtube = false, attempts = 1, stopAfter = Int.MAX_VALUE, budgetMs = ConnectPolicy.QUICK_BUDGET_MS,onSuccess={ passed.add(it.id) })
        if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return selectionNetworkChanged()
        if (ok > 0 && chooseCompared(additional.filter { it.id in passed },onFirst, eligible.filter { it.id !in attempted }) != null) {
            deepPending = true; return "Передача HTTPS проверена; облачные подсказки ${cloud.size}"
        }
        // Only if the first wave failed: bound DNS/TCP work to a small diverse wave, not thousands of subscriptions.
        val rest = foregroundDiscovery.window(eligible.filter { it.id !in attempted }, net)
        attempted.addAll(rest.map { it.id })
        val tcp = java.util.concurrent.ConcurrentHashMap<String, Int>(); val states = StateBatch()
        Tester.tcp(rest, parallel = 32, timeoutMs = 1200) { server, ms -> tcp[server.id] = ms; states.put(server.id) { it.copy(tcpMs = ms) } }
        states.flush()
        val candidates = rest.filter { (tcp[it.id] ?: 0) > 0 }.sortedBy { tcp[it.id] }.take(ForegroundDiscovery.HTTPS_FINALISTS)
        passed.clear()
        val second = realTest(candidates, "Авто: запасные ${candidates.size} маршрутов", big = false, youtube = false,
            attempts = 1, parallel = 6, stopAfter = Int.MAX_VALUE, budgetMs = ConnectPolicy.QUICK_BUDGET_MS,onSuccess={ passed.add(it.id) })
        if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return selectionNetworkChanged()
        val verified = if (second > 0) chooseCompared(candidates.filter { it.id in passed },onFirst, eligible.filter { it.id !in attempted }) else null
        if (verified != null) deepPending = true
        return if (verified != null) "Маршрут проверен передачей HTTPS; результаты сервисов отдельно" else "Нет маршрута с подтверждённой передачей данных; продолжаю поиск"
    }

    private fun expireUnreproducedSpeed(server: Server, net: String, at: Long) {
        Store.setState(server.id) { ServerChoice.invalidateSpeed(it, net, at) }
    }

    private suspend fun chooseCompared(candidates: List<Server>, onReady: ((Server) -> Unit)?,
                                       explore: List<Server> = emptyList()): Server? {
        val net = Net.key(app); val network = Net.underlying(app)?.toString()
        val deadline = android.os.SystemClock.elapsedRealtime() + AutoRoutePolicy.SEARCH_MS
        fun remaining() = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
        fun valid() = Net.key(app) == net && Net.underlying(app)?.toString() == network
        fun allowed() = !GameSession.active() && PhonePower.refresh(app).let { it.allowLight && !it.deferTraffic }
        val serviceAttempted = mutableSetOf<String>()
        suspend fun compare(pool: List<Server>, servicesChecked: Boolean = false): Server? {
            val before = Store.state.value
            val working = pool.filter { before.state(it).works && (before.settings.mode != Mode.AUTO || AutoEligibility.allowed(it)) }
            val missing = ServerChoice.diverse(working.filter { it.id !in serviceAttempted && AutoEligibility.serviceRank(before.state(it),net,System.currentTimeMillis()) != 0 },ServiceProbePolicy.FINALISTS)
            if (!servicesChecked && before.settings.mode == Mode.AUTO && missing.isNotEmpty() && remaining() >= 1500) {
                serviceAttempted.addAll(missing.map { it.id })
                kotlinx.coroutines.withTimeoutOrNull(minOf(ServiceProbePolicy.SERVICE_MS + 2000,remaining())) {
                    realTest(missing,"Авто: проверяю Telegram и YouTube",big=false,youtube=true,attempts=1,parallel=ServiceProbePolicy.PARALLEL,
                        budgetMs=minOf(ServiceProbePolicy.SERVICE_MS,remaining()))
                }
            }
            if (!valid()) return null
            val snapshot=Store.state.value
            val live=working.filter { snapshot.state(it).works }
            val acceptable = if (before.settings.mode == Mode.AUTO) AutoEligibility.connectionPool(live,{snapshot.state(it)},net,System.currentTimeMillis()) else live
            if (remaining() < AutoSelection.MIN_PROBE_MS) return acceptable.filter {
                GameSession.active() || ServerChoice.measuredSpeed(snapshot.state(it),net,System.currentTimeMillis()) > 0
            }.minWithOrNull(ServerChoice.downloadOrder({ snapshot.state(it) },net,System.currentTimeMillis()))
            return AutoSelection.compare(acceptable, { Store.state.value.state(it) }, net,
                now = { System.currentTimeMillis() }, elapsed = { android.os.SystemClock.elapsedRealtime() },
                validNetwork = { valid() },
                allowDownload = { !GameSession.active() && PowerPolicy.foregroundTransferAllowed(
                    PhonePower.refresh(app),hasLiveTunnel = Tunnel.socks != null) && remaining() >= AutoSelection.MIN_PROBE_MS }, gaming = GameSession.active(),
                hint = { maxOf(CloudControl.speed(it),CloudSpeed.speedOf(Report.serverKey(it))) },
                onInconclusive = { server, at -> expireUnreproducedSpeed(server,net,at) }) { server, budget ->
                val bounded = minOf(budget,remaining())
                if (bounded >= AutoSelection.MIN_PROBE_MS) kotlinx.coroutines.withTimeoutOrNull(bounded) {
                    realTest(listOf(server), "Авто: сравниваю скорость финалистов", big = true, youtube = false,
                        attempts = 1, parallel = 1, budgetMs = bounded)
                }
            }
        }
        var initial = compare(candidates)
        // Generic HTTPS may pass while both requested services fail. Continue with another bounded wave.
        if (initial == null && valid() && activeSearchReport?.resourceUnavailable() != true && remaining() >= 1500) {
            val wave = ForegroundDiscovery.serviceWave((candidates + explore).distinctBy { it.id }
                .filter(AutoEligibility::allowed), serviceAttempted)
            if (wave.isNotEmpty()) {
                kotlinx.coroutines.withTimeoutOrNull(minOf(ServiceProbePolicy.SERVICE_MS + 2000,remaining())) {
                    realTest(wave,"Авто: ищу Telegram и видео YouTube",big=false,youtube=true,
                        attempts=1,parallel=ServiceProbePolicy.PARALLEL,budgetMs=minOf(ServiceProbePolicy.SERVICE_MS,remaining()))
                }
                if (valid()) initial = compare(wave, servicesChecked = true)
            }
        }
        var winner = initial ?: run {
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
        if (Store.state.value.settings.mode == Mode.AUTO && AutoEligibility.connectionPool(listOf(winner),
                { Store.state.value.state(it) },net,System.currentTimeMillis()).isEmpty()) return null
        if (onReady != null) {
            RouteInsight.record(RouteInsight.Stage.FINALIST,
                if (GameSession.active()) RouteInsight.Criterion.GAME_PROFILE else RouteInsight.Criterion.DOWNLOAD,
                candidates.size, listOf(winner), net)
            onReady(winner)
        }
        return winner
    }

    /** Keep the working route unless an isolated HTTPS + download trial proves a faster mask here. */
    private suspend fun tuneAutoMask(server: Server, net: String, network: String?) {
        val start = Store.state.value
        if (start.settings.mode != Mode.AUTO) return
        val original = start.state(server).maskFor(net)
        val pinned = start.servers.firstOrNull { it.id == server.id } ?: server
        val trials = AutoRoutePolicy.maskTrials(pinned,original,start.settings)
        var expected = original
        for (id in trials) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (GameSession.active() || !PhonePower.refresh(app).let { it.allowLight && !it.deferTraffic }) return
            if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return
            var sample: Probe? = null
            kotlinx.coroutines.withTimeoutOrNull(2000L) {
                Tester.real(listOf(pinned to Masks.byId(id)),start.settings.testUrl,null,
                    youtube=true,batch=1,parallel=1,attempts=1,big=true,budgetMs=1900) { _,p -> sample=p }
            }
            val p = sample ?: continue
            if (Net.key(app) != net || Net.underlying(app)?.toString() != network) return
            val stamp = System.currentTimeMillis()
            var applied = false
            Store.setState(server.id) { current ->
                applied = current.maskFor(net) == expected && AutoRoutePolicy.serviceSafeImprovement(current,net,stamp,p)
                if (applied) {
                    AutoRoutePolicy.apply(current,net,id,stamp,p)
                } else current
            }
            if (applied) expected = id
        }
    }

    /** Find a VERIFIED spare while the existing TUN stays up. Selection changes only after returning success. */
    suspend fun prepareStandby(used: List<Server>): Server? {
        if (job?.isActive == true) return null
        val st = Store.state.value; val ids = used.map { it.id }.toSet(); val net = Net.key(app)
        val pool = preferred(st, st.servers.filter { it.id !in ids })
        val now = System.currentTimeMillis()
        val total = pool.sumOf { st.state(it).evidenceFor(net).checks.coerceIn(0, 10000) }.coerceAtMost(1_000_000)
        val ranked = pool.sortedByDescending { ServerChoice.blended(CloudSpeed.prior(it), st.state(it).evidenceFor(net), now) }
        val exploit = ServerChoice.diverse(ranked, 5)
        // One exploration slot, only for background verification; never an unverified live switch.
        val explore = pool.filter { s -> exploit.none { it.id == s.id } }.maxByOrNull {
            ServerChoice.blended(CloudSpeed.prior(it), st.state(it).evidenceFor(net), now) +
                .08 * ServerChoice.exploration(st.state(it).evidenceFor(net).checks, total)
        }
        val candidates = exploit + listOfNotNull(explore)
        var found: Server? = null
        val previous = progress.value
        try { realTest(candidates,"Фон: запасной маршрут для Telegram и YouTube",big=false,youtube=true,attempts=1,
            parallel=ServiceProbePolicy.PARALLEL,budgetMs=ServiceProbePolicy.SERVICE_MS)
            val current=Store.state.value
            found=AutoEligibility.connectionPool(candidates,{current.state(it)},net,System.currentTimeMillis())
                .minWithOrNull(ServerChoice.downloadOrder({current.state(it)},net,System.currentTimeMillis()))
        }
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
        val safe = list.filter(CloudControl::usable).let { if (st.settings.mode == Mode.AUTO) it.filter(AutoEligibility::allowed) else it }
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
        var recordDecision = false
        var decisionRoutes: List<Server> = emptyList()
        var decisionNet = ""
        var decisionSourceSize = 0
        var decisionCriterion = RouteInsight.Criterion.DOWNLOAD
        val ids = among?.map { it.id }?.toHashSet()
        runCatching { CloudSpeed.loadNet(app) }
        Store.update { st ->
            // one WireGuard tunnel per key (WARP accounts): two with the same key would steal the session from each other
            val works = st.servers.filter { (ids == null || it.id in ids) && st.state(it).works && CloudControl.usable(it) }
            // the balancer spreads connections over all selected servers: one that cannot reach Telegram would break
            // every few Telegram connections («иногда не работает»), so such servers stay out while others exist
            // (only in «Авто»: in «Серверы» the user's own choice stays as it is)
            val all = if (st.settings.mode == com.vlesscardvpn.model.Mode.AUTO) AutoEligibility.connectionPool(works.filter(AutoEligibility::allowed),{st.state(it)},Net.key(app),System.currentTimeMillis()) else works
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
            val sorted = ranked.distinctBy { if (it.protocol == "wireguard") "wg:" + it.secret else it.id }.take(if (st.settings.mode == Mode.AUTO) AutoEligibility.TOP else if (primary) 1 else n)
            val favs = if (st.settings.mode != Mode.AUTO && close && !primary) all.filter { it.id in st.settings.favorites } else emptyList()
            val best = ((if (st.settings.mode != Mode.AUTO && close && !primary) closeOnes(sorted.map(spread)).let { k -> sorted.take(k) } else sorted) + favs).map { it.id }.toSet()
            val oldSelected = st.states.filterValues { it.selected }.keys
            if (st.settings.mode == Mode.AUTO && oldSelected != best) {
                recordDecision = true
                decisionRoutes = sorted.filter { it.id in best }.take(RouteInsight.MAX_ROUTES)
                decisionNet = netKey
                decisionSourceSize = ids?.size ?: st.servers.size
                decisionCriterion = if (gaming) RouteInsight.Criterion.GAME_PROFILE else RouteInsight.Criterion.DOWNLOAD
            }
            c = best.size
            st.copy(states = st.servers.associate { s -> s.id to st.state(s).copy(selected = s.id in best) } )
        }
        if (recordDecision) RouteInsight.record(RouteInsight.Stage.SELECTED_POOL, decisionCriterion,
            decisionSourceSize, decisionRoutes, decisionNet)
        return c
    }

    /** Current strategy only: does the YouTube page load through it? */
    fun testByeDpi() = launch("Проверка обхода DPI") {
        val st = Store.state.value.settings
        val strategy = DpiStrategies.resolve(st, Net.key(app))
        val b = DpiProxy(app)
        try {
            val port = b.start(strategy, st.byeDpiSni, allowLocal = com.vlesscardvpn.BuildConfig.DEBUG)
            val p = withContext(Bg.io) { Tester.probeDpi(port) }
            if (p.ok) "${strategy.label}: YouTube открылся за ${p.ms} мс" else "${strategy.label}: не работает (${p.error})"
        } finally { b.close() }
    }

    // ---------- DPI strategy search (как в ByeByeDPI «тест стратегий») ----------
    data class DpiResult(val ok: Boolean, val ms: Int, val error: String, val score: Int = -1)
    /** Strategy id → result of the last search (this session). */
    val dpiResults = MutableStateFlow<Map<String, DpiResult>>(emptyMap())

    /**
     * Tries every strategy (custom line, the remembered one, 20 built-ins incl. zapret and own ones) through its
     * own local proxy, 4 at a time; a strategy works if the real YouTube page downloads. The best one in plan order
     * (gentlest first) is remembered for the current network and used automatically.
     */
    fun findDpi() = launch("Подбор обхода DPI") { doFindDpi() }

    /**
     * «Не работает? Починить»: one button for the user — check the network itself, fresh subscriptions + full re-test +
     * mask search (with evolution) + WARP, a DPI bypass for this network, then reconnect. [then] runs on the main thread.
     */
    fun fixAll(then: () -> Unit) = launch("Чиню всё сам") {
        if (!online()) return@launch "У самой сети нет интернета (ни ya.ru, ни 1.1.1.1 не открываются). Проверьте Wi-Fi/мобильные данные — VPN тут ни при чём."
        val n = runCatching { autoRescue(emptyList(), 3) { step -> progress.value = progress.value.copy(title = "Чиню: $step") } }.getOrDefault(0)
        val dpi = if (n == 0 || Store.state.value.settings.mode != com.vlesscardvpn.model.Mode.SERVERS) { progress.value = progress.value.copy(title = "Чиню: обход DPI"); doFindDpi() } else ""
        android.os.Handler(android.os.Looper.getMainLooper()).post(then)
        (if (n > 0) "Готово: рабочих серверов выбрано $n. " else "Серверы не нашлись — работаю через обход DPI. ") + dpi.take(160) + " Переподключаюсь…"
    }

    /** "1,2 ГБ" / "35 МБ" for the session traffic line. */
    /** 23400 kbit/s → "23 Мбит/с", 800 → "0.8 Мбит/с" */
    fun mbps(kbps: Int): String = if (kbps >= 10_000) "${kbps / 1000} Мбит/с" else "%.1f Мбит/с".format(java.util.Locale.US, kbps / 1000.0)

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f ГБ".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "${b shr 20} МБ"
        else -> "${b shr 10} КБ"
    }

    /**
     * Smart DPI search: 1) every strategy against the YouTube page (fast); 2) the 8 fastest that passed plus mutants
     * of the 3 best ByeDPI ones («эволюция стратегий») — two rounds against YouTube, its image CDN and Discord.
     * The winner opens the most sites most reliably, then is the fastest. The top 5 are remembered for this network:
     * if the chosen one stops working, the next is used at once instead of a new search.
     */
    suspend fun doFindDpi(): String {
        val st = Store.state.value.settings
        val network = Net.key(app)
        val probe = DpiProxy(app)
        val plan = DpiStrategies.plan(st, network, probe.available(DpiEngine.TPWS)).filter { probe.available(it.engine) }
        if (plan.isEmpty()) return "Обход DPI недоступен на этом устройстве"
        dpiResults.value = emptyMap()
        val done = AtomicInteger()
        step(0, plan.size)
        suspend fun run(list: List<DpiStrategy>, par: Int, test: (DpiStrategy, Int) -> DpiResult) = coroutineScope {
            val sem = Semaphore(par)
            list.map { s ->
                async(Bg.io) {
                    sem.withPermit {
                        val px = DpiProxy(app)
                        val r = try { test(s, px.start(s, st.byeDpiSni, allowLocal = com.vlesscardvpn.BuildConfig.DEBUG)) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { DpiResult(false, 0, Errors.human(e)) } finally { px.close() }
                        synchronized(dpiResults) { dpiResults.value = dpiResults.value + (s.id to r) }
                        step(done.incrementAndGet(), progress.value.total)
                    }
                }
            }.awaitAll()
        }
        // Every available engine/custom/evolved strategy participates. Bounded by phone pressure.
        val parallel = PhonePower.refresh(app).realParallel.coerceIn(1, 6)
        run(plan, parallel) { _, port ->
            Tester.probeDpi(port, attempts = 1).let { DpiResult(it.ok, it.ms, it.error) }
        }
        val first = dpiResults.value
        val passed = plan.filter { first[it.id]?.ok == true }.sortedBy { first.getValue(it.id).ms }
        // Stage 2: finals + evolution
        // Reserve a final for each working engine; fastest initial ping must not exclude zapret/Xray.
        val finals = (passed.groupBy { it.engine }.values.map { it.first() } + passed).distinctBy { it.id }.take(8)
        val evoPool = st.dpiEvoPool.mapNotNull { DpiEvo.decode(it) }.take(3)
        val mutants = if (finals.isEmpty()) DpiEvo.breed((evoPool + plan.filter { it.engine == DpiEngine.BYEDPI }).distinctBy { it.id }.take(8), 8)
            else DpiEvo.breed((finals.take(3) + evoPool).distinctBy { it.id }, 12)
        val stage2 = (finals + mutants).distinctBy { it.id }
        val scores = java.util.concurrent.ConcurrentHashMap<String, MutableList<Tester.DpiScore>>()
        if (stage2.isNotEmpty()) {
            progress.value = progress.value.copy(title = "Финал: YouTube, видео-CDN, Discord", done = 0, total = stage2.size * 2)
            done.set(0)
            repeat(2) {
                run(stage2, parallel.coerceAtMost(3)) { strat, port ->
                    var sc = Tester.probeDpiSites(port)
                    // a learning (--auto) strategy may spend the first connections on detection: one more chance
                    if (strat.adaptive && sc.score < Tester.DPI_SITES.size) sc = Tester.probeDpiSites(port).takeIf { it.score > sc.score } ?: sc
                    scores.getOrPut(strat.id) { java.util.Collections.synchronizedList(mutableListOf()) } += sc
                    DpiResult(sc.youtube, sc.ms, sc.error, sc.score)
                }
            }
        }
        // rank: total score over 2 rounds, YouTube both times, then speed
        fun total(id: String) = scores[id].orEmpty().sumOf { it.score }
        fun ytBoth(id: String) = scores[id].orEmpty().count { it.youtube }
        fun msOf(id: String) = scores[id].orEmpty().filter { it.ms > 0 }.map { it.ms }.average().takeIf { !it.isNaN() } ?: Double.MAX_VALUE
        val ranked = stage2.filter { ytBoth(it.id) > 0 }.sortedWith(compareByDescending<DpiStrategy> { ytBoth(it.id) }.thenByDescending { total(it.id) }.thenBy { msOf(it.id) })
        val best = ranked.firstOrNull() ?: passed.firstOrNull()
        val max = Tester.DPI_SITES.size * 2
        dpiResults.value = first + ranked.associate { it.id to DpiResult(true, msOf(it.id).toInt(), "", total(it.id)) }
        if (best != null && Net.key(app) == network) Store.update { a ->
            a.copy(settings = a.settings.copy(dpiRemembered = a.settings.dpiRemembered + (network to best.id) + (Settings.ANY_NETWORK to best.id),
                dpiRanking = a.settings.dpiRanking + (network to (ranked.ifEmpty { passed }).take(5).map { it.id }),
                dpiEvoPool = DpiEvo.keep(a.settings.dpiEvoPool, ranked.take(3).map { it.id })))
        }
        val evoWon = best?.id?.startsWith(DpiEvo.PREFIX) == true
        android.util.Log.i("E2E", "dpi engines passed=${passed.size} plan=${plan.size} " +
            DpiEngine.values().joinToString(" ") { e -> "${e.name.lowercase()}=${passed.count { it.engine == e }}" })
        return if (best == null) "Сеть «$network»: ни одна из ${plan.size} стратегий не открыла YouTube. Нужен сервер — режим «Авто» найдёт его сам."
        else "Сеть «$network»: YouTube открыли ${passed.size} из ${plan.size}, в финале ${stage2.size} (из них выведенных: ${mutants.size}). " +
            "Выбрана: ${best.label}" + (if (scores.containsKey(best.id)) " — ${total(best.id)} из $max проверок (YouTube, видео-CDN, Discord)" else "") +
            if (evoWon) ". Победила выведенная стратегия — такой нет ни в одном списке." else ""
    }

    /** ByeDPI-only stopped working: the next strategy remembered for this network (no new search). */
    fun nextDpi(): Boolean {
        val st = Store.state.value.settings
        val net = Net.key(app)
        val rank = (st.dpiRanking[net] ?: st.dpiRanking[Net.family(net)]).orEmpty()
        val cur = DpiStrategies.resolve(st, net).id
        if (st.dpiStrategy != Settings.DPI_AUTO || rank.size < 2) return false
        val next = rank[(rank.indexOf(cur) + 1) % rank.size].takeIf { it != cur } ?: return false
        Store.update { a -> a.copy(settings = a.settings.copy(dpiRemembered = a.settings.dpiRemembered + (net to next),
            dpiRanking = a.settings.dpiRanking + (net to (rank - cur + cur)))) }
        return true
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
            val a = ru.map { async(Bg.io) { reach(it) } }
            val b = foreign.map { async(Bg.io) { reach(it) } }
            val c = async(Bg.io) { bigYoutube() }
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
        val n = selectBest(10, wl)
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

    const val MAX_PER_SUB = 10000
}
