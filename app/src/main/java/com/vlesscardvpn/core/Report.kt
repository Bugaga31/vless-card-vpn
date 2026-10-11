package com.vlesscardvpn.core

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
    private const val EVERY = 6 * 3600_000L
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

    private fun anonId(ctx: Context): String = prefs(ctx).getString("id", null) ?: java.util.UUID.randomUUID().toString().take(12).also { prefs(ctx).edit().putString("id", it).apply() }

    /** Removes what could say who the user is or where they go: sites, addresses, numbers longer than 3 digits. */
    fun clean(line: String): String = line
        .replace(Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b"), "IP")
        .replace(Regex("\\b([a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}\\b"), "сайт")
        .replace(Regex("\\d{4,}"), "#").take(200)

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
            .put("profiles", profiles).put("ok", JSONArray(ok)).put("lines", JSONArray(lines.map { clean(it) }.distinct().take(20)))
    }

    private fun client() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private fun <T> call(block: (OkHttpClient) -> T): T {
        val c = client()
        return try { block(c) } catch (e: Exception) {
            val p = Tunnel.socks?.port ?: throw e
            block(c.newBuilder().proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", p))).build())
        }
    }

    /** From the autopilot (not in eco mode): pull the shared result and send this phone's report, each at most every 6 h. */
    fun auto(ctx: Context) {
        val app = ctx.applicationContext; val p = prefs(app); val now = System.currentTimeMillis()
        if (busy) return
        val pull = now - p.getLong("pulled", 0) > EVERY
        if (!p.contains("sent")) p.edit().putLong("sent", now - EVERY + 30 * 60_000L).apply() // first report ~30 min after install
        val send = available() && enabled(app) && now - p.getLong("sent", now) > EVERY
        if (!pull && !send) return
        busy = true
        scope.launch {
            try {
                if (pull) runCatching { pull(app) }.onSuccess { p.edit().putLong("pulled", now).apply() }
                if (send) runCatching { send(app) }.onSuccess { p.edit().putLong("sent", now).putString("status", "Отчёт отправлен " + hhmm(now)).apply() }
                    .onFailure { p.edit().putLong("sent", now - EVERY + 3600_000L).putString("status", "Отчёт не ушёл: ${Errors.human(it)}").apply() }
            } finally { busy = false }
        }
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
        val text = call { c -> c.newCall(Request.Builder().url(DATA).build()).execute().use { r -> if (r.code != 200) error("нет данных ${r.code}"); r.body!!.string() } }
        val o = JSONObject(text); val stamp = o.optString("stamp"); val p = prefs(ctx)
        if (stamp.isEmpty() || stamp == p.getString("stamp", "")) return
        o.optJSONObject("brain")?.let { Brain.blend(it, o.optInt("phones")) }
        val fam = Net.family(Net.key(ctx))
        o.optJSONObject("profiles")?.optString(fam)?.takeIf { it.startsWith(NetProfile.SCHEME) }?.let { link ->
            val prof = runCatching { NetProfile.decode(link) }.getOrNull()
            if (prof != null && !prof.isEmpty) Store.update { st -> NetProfile.apply(st, prof, Net.key(ctx)).first }
        }
        p.edit().putString("stamp", stamp).apply()
        runCatching { Brain.save(ctx) }
    }
}
