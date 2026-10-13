package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server

import android.content.Context
import com.vlesscardvpn.model.LinkParser
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * «Общее облако автора»: every phone sends a short anonymous report (no numbers, accounts, IP or visited sites) as an
 * issue to the author's private repository — what broke, which masks / DPI strategies won on which operator, the mini
 * model's weights and public servers that worked. The cloud job merges them (one model, one network setup per
 * operator, phone-verified servers, deduplicated problems) and publishes the result; phones pull it and start with
 * what the others already learned. The write key (Issues only, that one repository) is put in by the release build.
 */
object Report {
    const val REPO = "Bugaga31/vless-card-cloud"
    const val DATA = "https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/cloud.json"
    private const val EVERY = 6 * 3600_000L      // report at the latest
    private const val CHANGED = 3600_000L         // report sooner when something new happened
    private const val PULL = 20 * 60_000L         // shared result (tiny, ETag: usually "not modified")
    const val PHONES = "https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/phones"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var busy = false
    private val json = "application/json".toMediaType()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("report", 0)
    private fun token(): String = runCatching {
        String(com.vlesscardvpn.BuildConfig.REPORT_KEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).reversed()
    }.getOrDefault("")
    fun available() = token().startsWith("github_pat_") || token().startsWith("ghp_")
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("on", on).apply()
    fun lastText(ctx: Context): String = prefs(ctx).getString("status", "").orEmpty()

    fun phoneId(ctx: Context): String = anonId(ctx)
    private fun anonId(ctx: Context): String = prefs(ctx).getString("id", null) ?: java.util.UUID.randomUUID().toString().take(12).also { prefs(ctx).edit().putString("id", it).apply() }

    /** Removes what could say who the user is or where they go: sites, addresses, numbers longer than 3 digits. */
    fun clean(line: String): String = line
        .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b"), "IP")
        .replace(Regex("\\b([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}\\b"), "сайт")
        .replace(Regex("\\d{4,}"), "#").take(200)

    /** Short anonymous key of a server ("host:port" hashed): the cloud counts how many phones sit on each one. */
    fun serverKey(s: Server): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${s.address.lowercase()}:${s.port}".toByteArray()).take(4).joinToString("") { "%02x".format(it) }

    /** Cloud load: server key → phones connected to it in the last hours (from cloud.json "load"). */
    @Volatile var load: Map<String, Int> = emptyMap()
    fun loadFrom(ctx: Context) { if (load.isEmpty()) prefs(ctx).getString("load", null)?.let { load = parseLoad(it) } }
    fun parseLoad(json: String): Map<String, Int> = runCatching { JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.optInt(it) } } }.getOrDefault(emptyMap())

    /**
     * What this phone measured on its current network in the last 6 h, anonymous ([serverKey]): [key, kbit/s, ms, YouTube 1/0/-1].
     * ms 0 = it answered the ping but carried nothing — blocked here. The cloud sums this per operator.
     */
    fun speeds(st: AppState, now: Long): JSONArray {
        val recent = st.servers.filter { s -> publicSource(s.source) || s.source == PHONES }.map { it to st.state(it) }
            .filter { (_, ss) -> ss.realMs >= 0 && now - ss.checkedAt < 6 * 3600_000L && (ss.realMs > 0 || ss.tcpMs > 0) }
        val ok = recent.filter { it.second.realMs > 0 }.sortedByDescending { it.second.kbps }.take(30)
        val bad = recent.filter { it.second.realMs == 0 }.take(20)
        return JSONArray((ok + bad).map { (s, ss) -> JSONArray().put(serverKey(s)).put(ss.kbps.coerceAtLeast(0)).put(ss.realMs)
            .put(when (ss.ytOk) { true -> 1; false -> 0; null -> -1 }) })
    }

    /** Public servers only (from GitHub lists): never the user's own or paid subscriptions. */
    fun publicSource(src: String) = src.contains("githubusercontent.com") || src.contains("github.com/")

    fun build(id: String, version: String, sdk: Int, net: String, st: AppState, brain: String, lines: List<String>, now: Long): JSONObject {
        val fams = (listOf(Net.family(net)) + st.maskStats.keys.map { Net.family(it) }).filter { it != "Нет сети" }.distinct().take(4)
        val profiles = JSONObject()
        fams.forEach { f -> NetProfile.build(st, st.maskStats.keys.firstOrNull { Net.family(it) == f } ?: f).takeIf { !it.isEmpty }?.let { profiles.put(f, NetProfile.encode(it)) } }
        val ok = st.servers.filter { s -> publicSource(s.source) && st.state(s).let { it.realMs > 0 && now - it.checkedAt < 24 * 3600_000L } }
            .sortedBy { st.state(it).realMs }.take(15).mapNotNull { runCatching { LinkParser.toLink(it) }.getOrNull() }
        return JSONObject().put("id", id).put("v", version).put("sdk", sdk).put("net", Net.family(net))
            .put("brain", runCatching { JSONObject(brain) }.getOrNull() ?: JSONObject())
            .put("profiles", profiles).put("ok", JSONArray(ok))
            .put("use", JSONArray(st.servers.filter { st.state(it).selected && (publicSource(it.source) || it.source == PHONES) }.take(8).map { serverKey(it) }))
            .put("sp", speeds(st, now))
            .put("lines", JSONArray(lines.map { clean(it) }.distinct().take(20)))
    }

    private fun client() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private fun <T> call(block: (OkHttpClient) -> T): T {
        val c = client()
        return try { block(c) } catch (e: Exception) {
            val p = Tunnel.socks?.port ?: throw e
            block(c.newBuilder().proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", p))).build())
        }
    }

    /** From the autopilot: pull every 20 min, report hourly when changed (6 h otherwise). */
    @Synchronized fun auto(ctx: Context) {
        val app = ctx.applicationContext; val p = prefs(app); val now = System.currentTimeMillis()
        if (busy) return
        val pull = now - p.getLong("pulled", 0) > PULL
        if (!p.contains("sent")) p.edit().putLong("sent", now - EVERY + 30 * 60_000L).apply() // first report ~30 min after install
        val since = now - p.getLong("sent", now)
        val send = available() && enabled(app) && (since > EVERY || since > CHANGED && digest(app) != p.getInt("digest", 0))
        val applyCached = p.contains("cloud") && p.getString("appliedNet", "") != Net.key(app)
        if (!pull && !send && !applyCached) return
        busy = true
        scope.launch {
            try {
                // Network switch needs no download: the saved snapshot contains all operators.
                if (applyCached) runCatching { applyCloud(app, p.getString("cloud", "").orEmpty()) }
                if (pull) runCatching { pull(app) }.onSuccess { p.edit().putLong("pulled", now).apply() }.onFailure { p.edit().putLong("pulled", now - PULL + 5 * 60_000L).apply() }
                if (send) runCatching { send(app) }.onSuccess { p.edit().putLong("sent", now).putInt("digest", digest(app)).putString("status", "Отчёт отправлен " + hhmm(now)).apply() }
                    .onFailure { p.edit().putLong("sent", now - EVERY + 3600_000L).putString("status", "Отчёт не ушёл: ${Errors.human(it)}").apply() }
            } finally { busy = false }
        }
    }

    /** Changes when there is something new to tell: problems in the journal, masks learned, servers that worked. */
    private fun digest(ctx: Context): Int {
        val st = Store.state.value
        return (AppWatch.journal.value.map { clean(it.second) } + st.maskStats.map { it.key + it.value.size } +
            st.servers.filter { publicSource(it.source) && st.state(it).realMs > 0 }.take(15).map { it.id }).hashCode()
    }

    private fun hhmm(t: Long) = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(t))

    private fun send(ctx: Context) {
        runCatching { Brain.save(ctx) }
        val now = System.currentTimeMillis()
        val lines = AppWatch.journal.value.filter { now - it.first < EVERY }.map { it.second }
        val body = build(anonId(ctx), com.vlesscardvpn.BuildConfig.VERSION_NAME, android.os.Build.VERSION.SDK_INT, Net.key(ctx), Store.state.value,
            ctx.getSharedPreferences("brain", 0).getString("w", "").orEmpty(), lines, now)
        val issue = JSONObject().put("title", "[r] ${body.optString("net")} · ${body.optString("v")}").put("body", "```json\n$body\n```")
        call { c ->
            c.newCall(Request.Builder().url("https://api.github.com/repos/$REPO/issues").header("Authorization", "Bearer " + token())
                .header("Accept", "application/vnd.github+json").post(issue.toString().toRequestBody(json)).build()).execute().use { r ->
                if (r.code !in 200..201) error("облако ответило ${r.code}")
            }
        }
    }

    /** Shared result: the merged model and the network setup for this operator (applied once per new cloud version). */
    private fun pull(ctx: Context) {
        val p = prefs(ctx)
        val result = call { c -> c.newCall(Request.Builder().url(DATA).apply {
            if (p.contains("cloud")) p.getString("etag", null)?.let { header("If-None-Match", it) }
        }.build()).execute().use { r ->
            if (r.code == 304) return@use null
            if (r.code != 200) error("нет данных ${r.code}")
            r.body!!.string() to r.header("ETag") } }
        if (result == null) {
            p.getString("cloud", null)?.let { applyCloud(ctx, it) }
            return
        }
        applyCloud(ctx, result.first)
        // Save only after successful parsing/application, otherwise the next 304 could hide bad data.
        p.edit().putString("cloud", result.first).putString("etag", result.second).apply()
    }

    fun needsNetwork(stamp: String, appliedStamp: String, key: String, appliedKey: String): Boolean =
        stamp != appliedStamp || key != appliedKey

    private fun applyCloud(ctx: Context, text: String) {
        val p = prefs(ctx)
        val o = JSONObject(text); val stamp = o.optString("stamp")
        require(stamp.isNotEmpty()) { "облако без версии" }
        val key = Net.key(ctx); val fam = Net.family(key)
        if (needsNetwork(stamp, p.getString("netStamp", "").orEmpty(), key, p.getString("appliedNet", "").orEmpty())) {
            CloudSpeed.cacheNet(ctx, o.optJSONObject("net")?.toString() ?: "{}", fam)
            // Returning to a network must not count the same cloud masks as new successful tests.
            if (p.getString("profileStamp:$key", "") != stamp) {
                o.optJSONObject("profiles")?.optString(fam)?.takeIf { it.startsWith(NetProfile.SCHEME) }?.let { link ->
                    val prof = runCatching { NetProfile.decode(link) }.getOrNull()
                    if (prof != null && !prof.isEmpty) Store.update { st -> NetProfile.apply(st, prof, key).first }
                }
                p.edit().putString("profileStamp:$key", stamp).apply()
            }
            p.edit().putString("netStamp", stamp).putString("appliedNet", key).apply()
        }
        if (stamp == p.getString("stamp", "")) return
        o.optJSONObject("brain")?.let { Brain.blend(it, o.optInt("phones")) }
        load = parseLoad(o.optJSONObject("load")?.toString() ?: "{}")
        p.edit().putString("load", o.optJSONObject("load")?.toString() ?: "{}").apply()
        // servers that really worked on phones: a small list of their own, tried by the autopilot's rescue
        o.optJSONArray("servers")?.let { a -> (0 until minOf(a.length(), 40)).mapNotNull { LinkParser.parse(a.optString(it), PHONES) } }
            ?.takeIf { it.isNotEmpty() }?.let { list -> Store.update { st ->
                // the ones in use stay even when the cloud dropped them
                val keep = st.servers.filter { it.source != PHONES || st.state(it).selected }; val ids = keep.map { it.id }.toHashSet()
                st.copy(servers = keep + list.filter { ids.add(it.id) })
            } }
        p.edit().putString("stamp", stamp).apply()
        runCatching { Brain.save(ctx) }
    }
}
