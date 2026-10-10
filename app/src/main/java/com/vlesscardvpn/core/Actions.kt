package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.model.LinkParser
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.MaskLab
import com.vlesscardvpn.xray.Masks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val scope = CoroutineScope(SupervisorJob() + Bg.cpu + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.w("VLESS", "background job failed", e) })
    /** Only "is something running": buttons don't redraw the whole screen on every progress step. */
    val running: kotlinx.coroutines.flow.StateFlow<Boolean> = progress.map { it.running }.distinctUntilChanged()
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)
    private var job: Job? = null
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        // 1.0.81–1.0.83 left a mask neural network and maybe a 0.5–1.8 GB language model on the phone: removed, space freed
        runCatching { java.io.File(app.filesDir, "mask_brain.json").delete(); java.io.File(app.filesDir, "llm").deleteRecursively() }
    }

    fun cancel() { job?.cancel(); progress.value = progress.value.copy(running = false, message = "Остановлено") }

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
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()
            if (!body.isNullOrBlank() && LinkParser.parseMany(body).isNotEmpty()) return body to info
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
                async(Bg.io) {
                    sem.withPermit {
                        val got = fetchFull(url); val body = got?.first
                        got?.second?.let { inf -> Store.update { it.copy(settings = it.settings.copy(subInfo = it.settings.subInfo + (url to inf))) } }
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
        val alive = list.filter { (tcp[it.id] ?: 0) > 0 }.sortedBy { tcp[it.id] }.take(realLimit)
        // one attempt for all (fast), a second one only for those that failed although TCP answers
        var working = realTest(alive, "Проверка через Xray", big, attempts = 1)
        val again = Store.state.value.let { s -> alive.filter { !s.state(it).works } }
        if (again.isNotEmpty() && again.size < alive.size * 3 / 4 + 4) working += realTest(again, "Повторная проверка", big, attempts = 2)
        return "Работают: $working из ${alive.size} доступных по TCP (всего ${list.size})"
    }

    private suspend fun realTest(servers: List<Server>, title: String, big: Boolean = true, attempts: Int = 2, parallel: Int = 32): Int {
        if (servers.isEmpty()) return 0
        progress.value = progress.value.copy(title = title, done = 0, total = servers.size)
        val pinned = pinCertificates(servers)
        val st = Store.state.value
        val net = Net.key(app)
        val variants = pinned.map { it to (Masks.byId(st.state(it).maskFor(net))) }
        val (own, port) = dpiForTests(variants.map { it.second })
        val done = AtomicInteger(); val ok = AtomicInteger()
        val states = StateBatch(200)
        try {
            Tester.real(variants, st.settings.testUrl, port, own.ports, batch = 64, parallel = parallel, big = big, attempts = attempts) { i, p ->
                val s = variants[i].first
                if (p.works) ok.incrementAndGet()
                states.put(s.id) {
                    it.copy(realMs = p.realMs, bigOk = p.bigOk, ytOk = p.ytOk, tgOk = p.tgOk, checkedAt = System.currentTimeMillis(), kbps = if (p.kbps > 0) p.kbps else it.kbps,
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
        val whitelisted = Whitelist.last.value?.let { it.verdict == Whitelist.Verdict.WHITELIST && System.currentTimeMillis() - it.at < 3600_000L } == true
        val order = { s: Server ->
            val ok = { m: Mask -> Masks.compatible(m, s) && (dpiOk || !m.viaByeDpi) }
            val fresh = evoCand.filter(ok).shuffled().take(slots)
            val base = (Masks.searchOrder(dpiOk, s, cfg.maskFamilies, cfg.maskFps) + kept.filter(ok)).distinctBy { it.id }
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
        val (own, port) = dpiForTests(variants.map { it.second })
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
        try {
            Tester.real(variants, cfg.testUrl, port, own.ports, youtube = false, batch = 64, parallel = 12, attempts = 1,
                skip = { i -> System.currentTimeMillis() > deadline || synchronized(best) { (found[group(variants[i].first)] ?: 0) >= enough } || dead(variants[i].second) }) { i, p ->
                val (s, m) = variants[i]
                synchronized(best) {
                    val cur = best[s.id]
                    if (m != null && p !== Tester.SKIPPED) (if (p.works) kindOk else kindFail).merge(Masks.kind(m), 1, Int::plus)
                    if (p.works && m != null && (cur == null || eff(m, p) < eff(cur.first, cur.second))) best[s.id] = m to p
                    if (p.works && m != null) { good.getOrPut(s.id) { mutableListOf() } += m to p.realMs; found.merge(group(s), 1, Int::plus) }
                    if (m != null && p !== Tester.SKIPPED) tried += Triple(s.id, m.id, p.works)
                }
                step(done.incrementAndGet(), variants.size)
            }
        } finally { own.close() }
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
        val net = Net.key(app)
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
                    kbps = if (b.second.kbps > 0) b.second.kbps else it.kbps, ytOk = b.second.ytOk ?: it.ytOk,
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
    private suspend fun realSpeed(servers: List<Server>) {
        if (servers.isEmpty()) return
        val net = Net.key(app); val st = Store.state.value
        val variants = pinCertificates(servers).map { it to Masks.byId(st.state(it).maskFor(net)) }
        val (own, port) = dpiForTests(variants.map { it.second })
        try {
            val r = Tester.speedMany(variants, port, own.ports)
            Store.setStates(variants.indices.filter { r[it] > 0 }.associate { i -> variants[i].first.id to { x: com.vlesscardvpn.model.ServerState -> x.copy(kbps = r[i]) } })
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
            Store.setState(s.id) { it.copy(maskId = m.id, netMasks = it.netMasks + (net to m.id), realMs = p.realMs, bigOk = p.bigOk, kbps = p.kbps, checkedAt = System.currentTimeMillis()) }
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
        if (s.ytDpi.containsKey(net) || Tunnel.ytViaDpi) return false
        if (s.dpiRemembered[net] == null && s.dpiRemembered[Net.family(net)] == null) return false
        val vpn = Tunnel.socks?.port ?: return false
        val set = DpiSet(app)
        val (srv, dpi) = try {
            set.start(s, net, true, emptySet(), com.vlesscardvpn.BuildConfig.DEBUG)
            val dp = set.currentPort ?: return false
            var a = 0; var b = 0
            repeat(2) { a = maxOf(a, Tester.ytSpeed(vpn)); b = maxOf(b, Tester.ytSpeed(dp)) }
            a to b
        } catch (e: Throwable) { return false } finally { set.close() }
        val use = ytDpiWins(srv, dpi)
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
        val s1 = Store.state.value
        val top = s1.servers.filter { s1.state(it).works }.sortedBy { s1.state(it).score }.take(6)
        if (top.isEmpty()) return@launch "Рабочих серверов нет — сначала «Проверить серверы»."
        progress.value = progress.value.copy(title = "Ускоряю YouTube: меряю скорость")
        realSpeed(top)
        val s2 = Store.state.value
        selectBest(5, close = true, among = top.filter { s2.state(it).works })
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

    /** Whether the last [optimize] pass switched servers (TunnelService checks again sooner then). */
    @Volatile var lastOptimizeSwitched = false

    suspend fun optimize(): Boolean {
        lastOptimizeSwitched = false
        val st = Store.state.value
        if (!st.settings.autoOptimize || job?.isActive == true) return false
        if (st.settings.mode == com.vlesscardvpn.model.Mode.BYEDPI) return false
        val wifi = unmetered()
        var ytSwitch = false
        if (deepPending) {
            // connected right after the quick check: now test the rest calmly (outside the tunnel) and lighten heavy masks
            deepPending = false
            val prev0 = progress.value
            val s0 = Store.state.value
            val rest = s0.servers.filter { s0.state(it).tcpMs > 0 && (s0.state(it).realMs < 0 || System.currentTimeMillis() - s0.state(it).checkedAt > 6 * 3600_000L) }
                .sortedBy { s0.state(it).tcpMs }.take(if (wifi) 60 else 24)
            if (rest.isNotEmpty()) realTest(rest, "Фоновая проверка", big = true, attempts = 1)
            lightenMasks(wifi)
            // Wi-Fi: a real 3-second download through the 5 best (≈ what YouTube gets), not a 256 KB guess
            if (wifi) Store.state.value.let { s1 -> realSpeed(s1.servers.filter { s1.state(it).works }.sortedBy { s1.state(it).score }.take(5)) }
            ytSwitch = runCatching { decideYoutube() }.getOrDefault(false)
            progress.value = prev0
        }
        // Auto with nothing selected connects to the 5 best: compare against those.
        val sel = st.selected.ifEmpty { st.servers.filter { st.state(it).works }.sortedBy { st.state(it).score }.take(5) }
        val others = st.servers.filter { it !in sel && st.state(it).works }.sortedBy { st.state(it).score }.take(if (wifi) 12 else 6)
        val cand = (sel + others).distinctBy { it.id }
        if (cand.isEmpty()) return ytSwitch
        val prev = progress.value
        realTest(cand, "Фоновая проверка", big = wifi)
        progress.value = prev
        val now = Store.state.value
        val selOk = sel.filter { now.state(it).works }
        val best = cand.filter { now.state(it).works }.sortedBy { now.state(it).score }
        if (best.isEmpty()) {
            val alive = now.servers.filter { now.state(it).tcpMs > 0 }.sortedBy { now.state(it).tcpMs }.take(8)
            if (alive.isNotEmpty()) doFindMasks(alive, 24)
            val after = Store.state.value
            return if (alive.any { after.state(it).works }) { selectBest(5, close = true, among = alive.filter { after.state(it).works }); true } else ytSwitch
        }
        val median = selOk.map { now.state(it).score }.sorted().let { if (it.isEmpty()) Int.MAX_VALUE else it[it.size / 2] }
        val bestMs = now.state(best.first()).score
        // Ping is not everything: a server that answers fast but breaks big downloads (256 KB) feels slow — video stalls.
        val selBroken = wifi && selOk.isNotEmpty() && selOk.all { now.state(it).bigOk == false } && best.any { now.state(it).bigOk == true }
        val switch = selOk.size < sel.size || selBroken || (median > bestMs * 1.6 && median - bestMs > 150)
        lastOptimizeSwitched = switch
        android.util.Log.i("E2E", "optimize sel=${sel.size} ok=${selOk.size} median=$median best=$bestMs switch=$switch")
        val pick = if (selBroken) best.filter { now.state(it).bigOk == true } else best
        if (switch) selectBest(minOf(5, pick.size), close = true, among = pick)
        return ytSwitch || (switch && sel.map { it.id }.toSet() != Store.state.value.selected.map { it.id }.toSet())
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
     * the 5 fastest selected. Then [onReady] connects (servers, or ByeDPI if none passed).
     */
    fun autoConnect(onReady: () -> Unit) {
        val st = Store.state.value
        val working = st.servers.filter { st.state(it).works }
        val busy = job
        // Instant start: something worked before → connect now; the tunnel itself re-checks, heals and keeps searching.
        if (working.isNotEmpty() || st.servers.isEmpty() && Net.key(app).let { k -> st.settings.dpiRemembered[k] ?: st.settings.dpiRemembered[Net.family(k)] } != null) {
            if (busy?.isActive == true && progress.value.title == "Фоновая подготовка") cancel()
            val now = System.currentTimeMillis()
            val net = Net.key(app)
            recallNet(net)
            // Results older than 30 min (or from another network) may be dead: a 2–4 s check of the 12 best first,
            // otherwise the tunnel would connect to a dead server and lose much more time healing.
            // a network we know (its servers worked here and were checked within 3 h): connect at once, the tunnel re-checks itself
            val known = Store.state.value.let { s -> s.settings.netServers[net]?.let { ids -> s.servers.any { it.id in ids && s.state(it).works && now - s.state(it).checkedAt < 3 * 3600_000L } } } == true
            val stale = !known && (working.isNotEmpty() && working.none { now - st.state(it).checkedAt < 30 * 60_000L } || st.settings.netServers[net] == null && working.isNotEmpty() && st.settings.netServers.isNotEmpty())
            if (!stale || job?.isActive == true) {
                if (working.none { now - st.state(it).checkedAt < 6 * 3600_000L }) deepPending = true
                if (Store.state.value.let { s -> s.selected.none { s.state(it).works } }) selectBest(5, close = true, among = working)
                onReady(); return
            }
            // «Мгновенно»: old results are not waited for any more — connect right now to the 8 best at once; the
            // balancer (least ping, probes every 10 s) itself sends traffic through whichever of them answers fastest.
            // Meanwhile the quick check runs and narrows the set to the really fastest ones (seamless switch).
            selectBest(8, close = false, among = working)
            onReady()
            launch("Авто: быстрая проверка") { preflight(onReady, connected = true) }
            return
        }
        Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Готовлю серверы…", check = "Первый запуск в этой сети: ищу самые быстрые серверы, обычно 10–20 секунд")
        if (busy?.isActive == true) { scope.launch { busy.join(); autoPrepare(onReady) }; return }
        autoPrepare(onReady)
    }

    /** «Подключение…» tapped again while Auto is still preparing: stop preparing. */
    fun cancelPrepare() {
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
        working().let { w -> Store.state.value.let { st -> preferred(st, w).sortedBy { st.state(it).score }.take(12) } }.takeIf { it.isNotEmpty() }?.let {
            onStep("проверяю запасные серверы"); realTest(it, "Авто: проверяю запасные", big = false, attempts = 1)
        }
        working().takeIf { it.isNotEmpty() }?.let { return selectBest(5, close = true, among = it) }
        if (level < 2) return 0
        onStep("подбираю маскировку для доступных серверов")
        val st = Store.state.value
        val cand = (failed + st.servers.filter { st.state(it).tcpMs > 0 && it.id !in ids }.sortedBy { st.state(it).tcpMs }.take(10)).distinctBy { it.id }
        if (cand.isNotEmpty()) doFindMasks(cand, 24, budgetMs = 120_000)
        Store.state.value.let { s2 -> s2.servers.filter { s2.state(it).works } }.takeIf { it.isNotEmpty() }?.let { return selectBest(5, close = true, among = it) }
        if (level < 3) return 0
        onStep("обновляю подписки и перепроверяю все серверы")
        if (now - st.settings.lastSubRefresh > 3600_000L || st.servers.size < 20) doRefresh()
        doTestAll(Store.state.value.servers, 80)
        Store.state.value.let { s3 -> s3.servers.filter { s3.state(it).works } }.takeIf { it.isNotEmpty() }?.let { return selectBest(5, close = true, among = it) }
        val s4 = Store.state.value
        val c2 = s4.servers.filter { s4.state(it).tcpMs > 0 }.sortedBy { s4.state(it).tcpMs }.take(10)
        if (c2.isNotEmpty()) doFindMasks(c2, 24, budgetMs = 120_000)
        if (Store.state.value.servers.none { Masks.isWarp(it) && Store.state.value.state(it).works }) { onStep("настраиваю WARP"); runCatching { doSetupWarp(2, null) } }
        return Store.state.value.let { s5 -> s5.servers.filter { s5.state(it).works } }.let { if (it.isEmpty()) 0 else selectBest(5, close = true, among = it) }
    }

    /**
     * Network changed (Auto): the selected servers checked once in the new network (1–3 s, outside the tunnel); the dead
     * ones are dropped, and if none passes, the 12 best known are checked. True = something working is selected.
     */
    suspend fun quickRecheck(): Boolean {
        val st = Store.state.value
        val sel = st.selected
        if (sel.isEmpty() || job?.isActive == true) return false
        val prev = progress.value
        try {
            realTest(sel, "Проверка после смены сети", big = false, attempts = 1)
            var now = Store.state.value
            val okSel = sel.filter { now.state(it).works }
            if (okSel.isNotEmpty()) { if (okSel.size < sel.size) selectBest(okSel.size, close = true, among = okSel); return true }
            val ids = sel.map { it.id }.toSet()
            val cand = preferred(now, now.servers.filter { it.id !in ids && now.state(it).works }).sortedBy { now.state(it).score }.take(12)
            if (cand.isEmpty()) return false
            realTest(cand, "Проверка после смены сети", big = false, attempts = 1)
            now = Store.state.value
            val ok = cand.filter { now.state(it).works }
            if (ok.isEmpty()) return false
            selectBest(5, close = true, among = ok); return true
        } finally { progress.value = prev }
    }

    /** Instant start on old results: the selected + best known servers checked once (no big download), then connect. */
    private suspend fun preflight(onReady: () -> Unit, connected: Boolean = false): String {
        val st = Store.state.value
        val cand = (st.selected.filter { st.state(it).works } + preferred(st, st.servers.filter { st.state(it).works }).sortedBy { st.state(it).score })
            .distinctBy { it.id }.take(12)
        // the 6 best first: usually enough — connect right away instead of waiting for all 12
        realTest(cand.take(6), "Авто: проверяю серверы", big = false, attempts = 1)
        var now = Store.state.value
        if (cand.take(6).none { now.state(it).works } && cand.size > 6) { realTest(cand.drop(6), "Авто: проверяю серверы", big = false, attempts = 1); now = Store.state.value }
        val ok = cand.filter { now.state(it).works }
        if (ok.isNotEmpty()) {
            deepPending = true
            val before = Store.state.value.selected.map { it.id }.toSet()
            selectBest(5, close = true, among = ok)
            val after = Store.state.value.selected.map { it.id }.toSet()
            // already connected: switch (without a break) only when the fastest set really changed
            if (!connected || after != before) withContext(Dispatchers.Main) { onReady() }
            return if (connected) "Авто: самые быстрые сейчас — ${after.size} из ${ok.size} рабочих" + (if (after != before) ", переключился без разрыва" else "")
                else "Авто: работают ${ok.size} из ${cand.size} — подключаюсь"
        }
        if (!connected) Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Готовлю серверы…", check = "Прежние серверы здесь не работают: ищу другие, обычно 10–20 секунд")
        return doAutoPrepare(onReady)
    }

    private fun autoPrepare(onReady: () -> Unit) = launch("Авто-настройка") { doAutoPrepare(onReady) }

    private suspend fun doAutoPrepare(onReady: () -> Unit): String {
        val now = System.currentTimeMillis()
        val st0 = Store.state.value
        val notes = mutableListOf<String>()
        if (st0.servers.isEmpty() || st0.settings.autoUpdateSubs && now - st0.settings.lastSubRefresh > 12 * 3600_000L) {
            progress.value = progress.value.copy(title = "Авто: обновляю подписки"); notes += doRefresh()
        }
        fun fresh() = Store.state.value.let { st -> st.servers.filter { st.state(it).works && now - st.state(it).checkedAt < 6 * 3600_000L } }
        if (fresh().size < 3 && Store.state.value.servers.isNotEmpty()) {
            // Quick start: ping everything in a few seconds, test only the 30 nearest — connect to the best of them.
            // The rest is tested in the background after connecting (optimize → deepPending) and switched to if faster.
            notes += quickTest(Store.state.value.servers)
            if (fresh().isEmpty()) {
                progress.value = progress.value.copy(title = "Авто: проверяю ещё серверы")
                val st = Store.state.value
                realTest(st.servers.filter { st.state(it).tcpMs > 0 && st.state(it).realMs < 0 }.sortedBy { st.state(it).tcpMs }.take(60), "Авто: проверяю ещё серверы", big = true, attempts = 1)
            }
            deepPending = true
        }
        if (fresh().isEmpty()) {
            val st = Store.state.value
            val candidates = st.servers.filter { st.state(it).tcpMs > 0 && !st.state(it).works }.sortedBy { st.state(it).tcpMs }.take(10)
            progress.value = progress.value.copy(title = "Авто: подбираю маскировку")
            if (candidates.isNotEmpty()) notes += doFindMasks(candidates, 24, enough = 1, budgetMs = 60_000)
        }
        val working = fresh()
        if (working.isEmpty()) {
            val st = Store.state.value.settings
            if (Net.key(app).let { k -> st.dpiRemembered[k] ?: st.dpiRemembered[Net.family(k)] } == null) { progress.value = progress.value.copy(title = "Авто: подбираю обход DPI"); notes += doFindDpi() }
        }
        val n = selectBest(5, close = true, among = working)
        withContext(Dispatchers.Main) { onReady() }
        return if (n > 0) "Авто: выбрано $n лучших серверов — подключаюсь" else "Авто: рабочих серверов нет — подключаюсь через обход DPI без сервера"
    }

    /** «Авто», first time on a network: all servers pinged at once (1.5 s), the 30 nearest really tested (speed + YouTube). */
    suspend fun quickTest(list: List<Server>): String {
        progress.value = progress.value.copy(title = "Авто: пинг ${list.size} серверов", done = 0, total = list.size)
        val done = AtomicInteger(); val batch = StateBatch(); val tcp = java.util.concurrent.ConcurrentHashMap<String, Int>()
        Tester.tcp(list, parallel = 192, timeoutMs = 1500) { s, ms ->
            tcp[s.id] = ms
            batch.put(s.id) { it.copy(tcpMs = ms, realMs = if (ms == 0) 0 else it.realMs, checkedAt = System.currentTimeMillis()) }
            step(done.incrementAndGet(), list.size)
        }
        batch.flush()
        val near = list.filter { (tcp[it.id] ?: 0) > 0 }.sortedBy { tcp[it.id] }.take(30)
        val ok = realTest(near, "Авто: проверяю 30 ближайших", big = true, attempts = 1)
        return "Быстрая проверка: работают $ok из ${near.size} ближайших (всего ${list.size})"
    }

    /** Subscriptions older than 12 h are refreshed in the background on app start. */
    fun maybeAutoRefresh() {
        val st = Store.state.value.settings
        if (st.autoUpdateSubs && System.currentTimeMillis() - st.lastSubRefresh > 12 * 3600_000L) refreshSubscriptions()
    }

    /** «Страна»: only servers of the chosen country when at least one of them works, otherwise all. */
    fun preferred(st: AppState, list: List<Server>): List<Server> {
        val c = st.settings.country
        if (c.isEmpty()) return list
        return list.filter { Countries.of(it.name) == c }.ifEmpty { list }
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
        val k = scores.count { it <= b * 1.6 + 150 }
        return if (k >= 2 || scores.size < 2) k else if (scores[1] <= b * 3 + 300) 2 else 1
    }

    /** Selects the [n] fastest working servers (deselects others). */
    fun selectBest(n: Int = 5, among: Collection<Server>? = null, close: Boolean = false): Int {
        var c = 0
        val ids = among?.map { it.id }?.toHashSet()
        Store.update { st ->
            // one WireGuard tunnel per key (WARP accounts): two with the same key would steal the session from each other
            val all = st.servers.filter { (ids == null || it.id in ids) && st.state(it).works }
            val sorted = preferred(st, all).sortedBy { st.state(it).score }
                .distinctBy { if (it.protocol == "wireguard") "wg:" + it.secret else it.id }.take(n)
            val favs = if (close) all.filter { it.id in st.settings.favorites } else emptyList()
            val best = ((if (close) closeOnes(sorted.map { st.state(it).score }).let { k -> sorted.take(k) } else sorted) + favs).map { it.id }.toSet()
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
                            catch (e: Throwable) { DpiResult(false, 0, Errors.human(e)) } finally { px.close() }
                        synchronized(dpiResults) { dpiResults.value = dpiResults.value + (s.id to r) }
                        step(done.incrementAndGet(), progress.value.total)
                    }
                }
            }.awaitAll()
        }
        // Stage 1: one attempt each, 12 at a time; after 12 passes the rest is skipped (the final re-checks them twice anyway)
        val passes = AtomicInteger()
        run(plan, 12) { _, port ->
            if (passes.get() >= 12) DpiResult(false, 0, "пропущена: уже хватает рабочих")
            else Tester.probeDpi(port, attempts = 1).let { if (it.ok) passes.incrementAndGet(); DpiResult(it.ok, it.ms, it.error) }
        }
        val first = dpiResults.value
        val passed = plan.filter { first[it.id]?.ok == true }.sortedBy { first.getValue(it.id).ms }
        // Stage 2: finals + evolution
        val finals = passed.take(8)
        val evoPool = st.dpiEvoPool.mapNotNull { DpiEvo.decode(it) }.take(3)
        val mutants = if (finals.isEmpty()) DpiEvo.breed((evoPool + plan.filter { it.engine == DpiEngine.BYEDPI }).distinctBy { it.id }.take(8), 8)
            else DpiEvo.breed((finals.take(3) + evoPool).distinctBy { it.id }, 12)
        val stage2 = (finals + mutants).distinctBy { it.id }
        val scores = java.util.concurrent.ConcurrentHashMap<String, MutableList<Tester.DpiScore>>()
        if (stage2.isNotEmpty()) {
            progress.value = progress.value.copy(title = "Финал: YouTube, видео-CDN, Discord", done = 0, total = stage2.size * 2)
            done.set(0)
            repeat(2) {
                run(stage2, 6) { strat, port ->
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
        if (best != null) Store.update { a ->
            a.copy(settings = a.settings.copy(dpiRemembered = a.settings.dpiRemembered + (network to best.id) + (Settings.ANY_NETWORK to best.id),
                dpiRanking = a.settings.dpiRanking + (network to (ranked.ifEmpty { passed }).take(5).map { it.id }),
                dpiEvoPool = DpiEvo.keep(a.settings.dpiEvoPool, ranked.take(3).map { it.id })))
        }
        val evoWon = best?.id?.startsWith(DpiEvo.PREFIX) == true
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
