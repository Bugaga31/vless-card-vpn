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
import kotlinx.coroutines.launch
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
            if (Tunnel.status.value.state == Tunnel.State.CONNECTED)
                add(http.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", Store.state.value.settings.socksPort))).build())
        }
        for (c in clients) for (u in Settings.mirrors(url)) {
            val body = runCatching {
                c.newCall(Request.Builder().url(u).header("User-Agent", "v2rayNG/1.10").build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }.getOrNull()
            if (!body.isNullOrBlank() && LinkParser.parseMany(body).isNotEmpty()) return body
        }
        return null
    }

    fun refreshSubscriptions(then: (() -> Unit)? = null) = launch("Обновление подписок") {
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
        if (ok == 0) "Подписки не загрузились (нет сети или всё заблокировано)" else "Загружено серверов: $total из $ok подписок"
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

    private suspend fun byeDpiPortForTests(need: Boolean): Pair<ByeDpi?, Int?> {
        if (!need) return null to null
        val running = Tunnel.byeDpiPort
        if (running != null) return null to running
        val b = ByeDpi(app)
        if (!b.available) return null to null
        val st = Store.state.value.settings
        return runCatching { b to b.start(st.byeDpiArgs, st.byeDpiSni) }.getOrElse { b.close(); null to null }
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
        val (own, port) = byeDpiPortForTests(variants.any { it.second?.viaByeDpi == true })
        val done = AtomicInteger(); val ok = AtomicInteger()
        try {
            Tester.real(variants, st.settings.testUrl, port) { i, p ->
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
    fun findMasks(servers: List<Server>, perServer: Int = 32) = launch("Подбор маскировки") {
        if (servers.isEmpty()) return@launch "Выберите серверы"
        val pinned = pinCertificates(servers)
        val (own, port) = byeDpiPortForTests(true)
        val variants: List<Pair<Server, Mask?>> = pinned.flatMap { s -> Masks.searchOrder(port != null, s).take(perServer).map { s to it } }
        val best = HashMap<String, Pair<Mask, Probe>>()
        val done = AtomicInteger()
        try {
            Tester.real(variants, Store.state.value.settings.testUrl, port, youtube = false, batch = 48, parallel = 8) { i, p ->
                val (s, m) = variants[i]
                synchronized(best) {
                    val cur = best[s.id]
                    if (p.works && m != null && (cur == null || p.realMs < cur.second.realMs)) best[s.id] = m to p
                }
                step(done.incrementAndGet(), variants.size)
            }
        } finally { own?.close() }
        pinned.forEach { s ->
            val b = best[s.id]
            Store.setState(s.id) {
                if (b == null) it.copy(realMs = 0, checkedAt = System.currentTimeMillis())
                else it.copy(maskId = b.first.id, realMs = b.second.realMs, bigOk = b.second.bigOk, checkedAt = System.currentTimeMillis())
            }
        }
        "Маскировка найдена для ${best.size} из ${pinned.size} серверов (перебрано ${variants.size} вариантов)"
    }

    /** Selects the [n] fastest working servers (deselects others). */
    fun selectBest(n: Int = 5): Int {
        var c = 0
        Store.update { st ->
            val best = st.servers.filter { st.state(it).works }.sortedBy { st.state(it).realMs }.take(n).map { it.id }.toSet()
            c = best.size
            st.copy(states = st.servers.associate { s -> s.id to st.state(s).copy(selected = s.id in best) } )
        }
        return c
    }

    /** ByeDPI-only check: is the internet reachable through the built-in ByeDPI with the current strategy? */
    fun testByeDpi() = launch("Проверка ByeDPI") {
        val (own, port) = byeDpiPortForTests(true)
        if (port == null) return@launch "ByeDPI не запустился — проверьте стратегию"
        try {
            val p = withContext(Dispatchers.IO) { Tester.probeSocks(port, Store.state.value.settings.testUrl) }
            if (p.realMs > 0) "ByeDPI: ответ ${p.realMs} мс, 256 КБ: ${yn(p.bigOk)}, YouTube: ${yn(p.ytOk)}" else "ByeDPI: нет ответа (${p.error})"
        } finally { own?.close() }
    }

    fun yn(b: Boolean?) = when (b) { true -> "да"; false -> "нет"; null -> "—" }

    const val MAX_PER_SUB = 600
}
