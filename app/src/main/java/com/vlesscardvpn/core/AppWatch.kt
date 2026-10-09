package com.vlesscardvpn.core

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * «Под приложение»: sees which app is open (usage access) and tunes the VPN for it:
 * Gemini/ChatGPT/Claude/Spotify… → servers of a country where the service works (and GPS there, if allowed);
 * YouTube → «Ускорить YouTube» once per network. Rule-based, runs on the phone, no network calls of its own.
 */
object AppWatch {
    data class Need(val title: String, val avoid: Set<String> = emptySet(), val youtube: Boolean = false)

    val AI_BAD = setOf("RU", "BY", "CN", "HK", "MO", "IR", "KP", "SY", "CU", "VE")
    private val MEDIA_BAD = setOf("RU", "BY", "CN", "IR", "KP", "SY", "CU")
    /** AI services: always through the servers (never direct / DPI bypass), even with «Через VPN только…». */
    val AI_DOMAINS = listOf("domain:gemini.google.com", "domain:gemini.google", "domain:bard.google.com", "domain:aistudio.google.com",
        "domain:ai.google.dev", "domain:generativelanguage.googleapis.com", "domain:alkalimakersuite-pa.clients6.google.com",
        "domain:proactivebackend-pa.googleapis.com", "domain:robinfrontend-pa.googleapis.com", "domain:geller-pa.googleapis.com",
        "domain:assistant-s3-pa.googleapis.com", "domain:notebooklm.google", "domain:notebooklm.google.com", "domain:labs.google",
        "domain:deepmind.google", "domain:openai.com", "domain:chatgpt.com", "domain:oaistatic.com", "domain:oaiusercontent.com",
        "domain:anthropic.com", "domain:claude.ai", "domain:perplexity.ai", "domain:copilot.microsoft.com", "domain:x.ai", "domain:grok.com")
    val PREFER = listOf("US", "NL", "DE", "FI", "GB", "FR", "SE", "PL", "CA", "JP", "SG")

    val PROFILES: Map<String, Need> = mapOf(
        "com.google.android.apps.bard" to Need("Gemini", AI_BAD),
        "com.google.android.googlequicksearchbox" to Need("Gemini (Google)", AI_BAD),
        "ai.x.grok" to Need("Grok", AI_BAD),
        "com.openai.chatgpt" to Need("ChatGPT", AI_BAD),
        "com.anthropic.claude" to Need("Claude", AI_BAD),
        "com.microsoft.copilot" to Need("Copilot", AI_BAD),
        "ai.perplexity.app.android" to Need("Perplexity", AI_BAD),
        "com.spotify.music" to Need("Spotify", MEDIA_BAD),
        "com.netflix.mediaclient" to Need("Netflix", MEDIA_BAD),
        "com.google.android.youtube" to Need("YouTube", youtube = true),
        "com.google.android.apps.youtube.music" to Need("YouTube Music", youtube = true),
        "app.revanced.android.youtube" to Need("YouTube", youtube = true),
    )

    /** Country to switch to for this app, or null when the current servers already fit (or nothing better works). */
    fun decide(need: Need, current: List<String>, available: List<Pair<String, Int>>, remembered: String? = null): String? {
        if (need.avoid.isEmpty()) return null
        val bad = current.isEmpty() || current.any { it in need.avoid } || current.all { it.isEmpty() }
        if (!bad) return null
        val ok = available.filter { it.first.isNotEmpty() && it.first !in need.avoid && it.second > 0 }
        if (remembered != null && ok.any { it.first == remembered }) return remembered
        return PREFER.firstOrNull { p -> ok.any { it.first == p } } ?: ok.maxByOrNull { it.second }?.first
    }

    /** Country for the fake GPS: the chosen «Страна», otherwise the first known country among the servers in use. */
    fun gpsCountry(chosen: String, current: List<String>): String = chosen.ifEmpty { current.firstOrNull { it.isNotEmpty() } ?: "" }

    /**
     * «Автопилот помощника»: what to do now. fails = checks in a row that said «no internet»; heavy = slow mask in use.
     * "fix" → «Починить» (servers, masks, DPI), "lighten" → light masks; rate-limited so it never fights the user.
     */
    fun pilot(fails: Int, heavy: Boolean, busy: Boolean, sinceFix: Long, sinceLight: Long, sinceEvolve: Long = 0, slow: Boolean = false, sinceSpeedup: Long = 0): String? = when {
        busy -> null
        fails >= 2 && sinceFix > 3 * 60_000L -> "fix"
        heavy && sinceLight > 3600_000L -> "lighten"
        slow && sinceSpeedup > 90 * 60_000L -> "speedup"
        fails == 0 && sinceEvolve > 2 * 3600_000L -> "evolve"
        else -> null
    }
    private var fails = 0; private var lastFix = 0L; private var lastLight = 0L; private var tick = 0
    private var lastSpeedup = 0L; private var lastSpeedCheck = System.currentTimeMillis() - 35 * 60_000L
    @Volatile var lastMbps = 0.0
    private var evolveNet = ""
    private var lastEvolve = System.currentTimeMillis() - 100 * 60_000L // first evolution ~20 min after start
    /** What the autopilot did (newest first): time + text, shown in «Помощник». */
    val journal = MutableStateFlow<List<Pair<Long, String>>>(emptyList())

    private suspend fun autopilot(reconnect: () -> Unit) {
        if (++tick % 40 != 0) return // once a minute
        val s = Tunnel.status.value
        fails = if (s.checkOk == false) fails + 1 else 0
        val now = System.currentTimeMillis()
        // new network: masks that passed elsewhere may not pass here — evolve here ~15 min later instead of waiting 2 h
        val net = appCtx?.let { runCatching { Net.key(it) }.getOrNull() }.orEmpty()
        if (net.isNotEmpty() && net != evolveNet) { evolveNet = net; lastEvolve = minOf(lastEvolve, now - 105 * 60_000L) }
        // light speed check (4 s) every 45 min, only on unmetered networks (Wi-Fi) — mobile data is not spent on it
        var slow = false
        if (now - lastSpeedCheck > 45 * 60_000L && fails == 0 && !Actions.progress.value.running && unmetered()) {
            lastSpeedCheck = now
            val port = Tunnel.socks?.port
            if (port != null) { lastMbps = withContext(Dispatchers.IO) { runCatching { Tester.speed(port, 4).mbps }.getOrDefault(0.0) }; slow = lastMbps in 0.01..3.0 }
        }
        when (pilot(fails, Store.summary.value.heavyMask, Actions.progress.value.running, now - lastFix, now - lastLight, now - lastEvolve, slow, now - lastSpeedup)) {
            "speedup" -> { lastSpeedup = now; say("Автопилот: скорость всего %.1f Мбит/с — ищу серверы и маски быстрее".format(lastMbps)); withContext(Dispatchers.Main) { Actions.boostYoutube() } }
            "evolve" -> {
                lastEvolve = now
                val sel = Store.state.value.selected
                if (sel.isNotEmpty()) {
                    say("Автопилот: эволюция масок — скрещиваю и проверяю мутантов рабочих масок в этой сети")
                    withContext(Dispatchers.Main) { Actions.findMasks(sel.take(3), perServer = 12) }
                    delay(1000); while (Actions.progress.value.running) delay(1000)
                    Actions.progress.value.message.takeIf { it.isNotEmpty() }?.let { say("Эволюция: $it") }
                }
            }
            "fix" -> { lastFix = now; fails = 0; say("Автопилот: интернет через VPN пропал — чиню сам (серверы, маски, обход DPI)"); withContext(Dispatchers.Main) { Actions.fixAll { reconnect() } } }
            "lighten" -> { lastLight = now; say("Автопилот: маска тяжёлая, видео может тормозить — ищу лёгкую"); withContext(Dispatchers.Main) { Actions.lighten() } }
        }
    }

    val status = MutableStateFlow("")
    private var job: Job? = null
    private val ytDone = HashMap<String, Long>()
    /** app → exit country that worked for it (picked first next time). */
    private val goodFor = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun hasAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        val m = if (android.os.Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
            else ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        m == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun openAccess(ctx: Context) = runCatching {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun foreground(ctx: Context): String? = runCatching {
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        val ev = um.queryEvents(now - 15_000, now); val e = android.app.usage.UsageEvents.Event(); var last: String? = null
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == 1 /* ACTIVITY_RESUMED */) last = e.packageName }
        last
    }.getOrNull()

    private fun countries(): List<String> = Store.state.value.selected.map { Countries.of(it.name) }

    /** Runs while the VPN is on. reconnect() re-applies the servers without dropping the tunnel. */
    fun start(ctx: Context, reconnect: () -> Unit) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext; appCtx = app
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            var last = ""
            try {
                while (isActive && Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                    val st = Store.state.value.settings
                    if (st.gpsSpoof) GpsMock.follow(app, gpsCountry(st.country, countries())) else GpsMock.stop(app)
                    val pkg = if (st.appAware && hasAccess(app)) foreground(app) else null
                    if (pkg != null && pkg != last) { last = pkg; PROFILES[pkg]?.let { handle(app, it, reconnect) } }
                    if (st.autopilot) autopilot(reconnect)
                    delay(1500)
                }
            } finally { GpsMock.stop(app); status.value = "" }
        }
    }

    private suspend fun handle(app: Context, need: Need, reconnect: () -> Unit) {
        if (need.youtube) {
            val net = Net.key(app); val t = ytDone[net] ?: 0
            if (Store.state.value.settings.smartYoutube && System.currentTimeMillis() - t > 6 * 3600_000L && !Actions.progress.value.running) {
                ytDone[net] = System.currentTimeMillis()
                say("Открыт ${need.title}: ускоряю видео для этой сети")
                withContext(Dispatchers.Main) { Actions.boostYoutube() }
            }
            return
        }
        // what the internet really sees (Cloudflare trace through the tunnel) beats what the server names say
        val exit = Tunnel.socks?.port?.let { p -> withContext(Dispatchers.IO) { Tester.exitCountry(p) } }
        val cur = if (exit != null) listOf(exit) else countries()
        if (exit != null && exit !in need.avoid) { goodFor[need.title] = exit; status.value = "${need.title}: выход в ${Countries.title(exit)} — подходит"; return }
        val target = decide(need, cur, Actions.countries(Store.state.value).filter { it.first != exit }, goodFor[need.title])
        if (target == null) { say("Открыт ${need.title}: выход ${exit?.let { Countries.title(it) } ?: "в неизвестной стране"}, а серверов нужной страны нет — добавьте серверы США/Европы"); return }
        Store.update { it.copy(settings = it.settings.copy(country = target)) }
        val n = Actions.selectBest(5, close = true)
        if (n > 0) { reconnect(); say("Открыт ${need.title}: переключил на серверы ${Countries.title(target)}" + if (Store.state.value.settings.gpsSpoof) ", GPS тоже там" else "") }
    }

    private var appCtx: Context? = null
    private fun unmetered(): Boolean = runCatching {
        val cm = appCtx!!.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.firstOrNull { !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        n?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    private fun say(t: String) { status.value = t; journal.value = (listOf(System.currentTimeMillis() to t) + journal.value).take(20); Assistant.note(t) }
}
