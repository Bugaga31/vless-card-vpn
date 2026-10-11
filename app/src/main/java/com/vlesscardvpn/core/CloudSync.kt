package com.vlesscardvpn.core

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * «Облако на вашем GitHub»: what the app learned (masks per network, DPI strategies, server results, the mini
 * model) is saved as a backup file in the user's own private GitHub repository and restored on a new phone or after
 * reinstalling — masks don't have to be learned again. The token (fine-grained, «Contents: read and write» on that one
 * repository) stays only on this phone, outside settings and backups; nothing goes to anyone else's server.
 */
object CloudSync {
    const val FILE = "vless-card-backup.txt"
    data class State(val text: String = "", val busy: Boolean = false)
    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastHash = 0
    private var lastAuto = 0L
    private val json = "application/json".toMediaType()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cloud", 0)
    fun repo(ctx: Context): String = prefs(ctx).getString("repo", "").orEmpty()
    fun hasToken(ctx: Context) = prefs(ctx).getString("token", "").orEmpty().isNotEmpty()
    fun configured(ctx: Context) = repo(ctx).isNotEmpty() && hasToken(ctx)

    /** "user/repo", "https://github.com/user/repo(.git)" → "user/repo"; null when it doesn't look like a repository. */
    fun normalize(input: String): String? {
        val s = input.trim().removeSuffix("/").removeSuffix(".git").substringAfter("github.com/")
        val p = s.split('/').filter { it.isNotEmpty() }
        return if (p.size == 2 && p.all { Regex("[A-Za-z0-9_.-]+").matches(it) }) "${p[0]}/${p[1]}" else null
    }

    fun save(ctx: Context, repo: String, token: String): String? {
        val r = normalize(repo) ?: return "Репозиторий в виде имя/репо, например eden/vpn-backup"
        prefs(ctx).edit().putString("repo", r).apply { if (token.isNotBlank()) putString("token", token.trim()) }.apply()
        return null
    }

    fun forget(ctx: Context) { prefs(ctx).edit().clear().apply(); state.value = State("Облако отключено, токен удалён с телефона") }

    private fun client() = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        .let { c -> Tunnel.socks?.port?.let { p -> c to c.newBuilder().proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", p))).build() } ?: (c to null) }

    private fun <T> call(block: (OkHttpClient) -> T): T { val (d, v) = client(); return try { block(d) } catch (e: Exception) { if (v != null) block(v) else throw e } }

    private fun req(ctx: Context, path: String) = Request.Builder().url("https://api.github.com/repos/${repo(ctx)}/contents/$path")
        .header("Authorization", "Bearer " + prefs(ctx).getString("token", "")).header("Accept", "application/vnd.github+json")

    /** Uploads the backup (only when something changed, unless [force]). */
    fun upload(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!configured(app) || state.value.busy) return
        scope.launch {
            runCatching { Brain.save(app) }
            val text = Backup.pack(Store.state.value) + "\n" + brain(app)
            if (!force && text.hashCode() == lastHash) return@launch
            if (force) state.value = State("Сохраняю в облако…", true)
            state.value = runCatching {
                call { c ->
                    val sha = c.newCall(req(app, FILE).get().build()).execute().use { r -> if (r.code == 200) org.json.JSONObject(r.body!!.string()).optString("sha") else "" }
                    val body = org.json.JSONObject().put("message", "VLESS Card backup " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()))
                        .put("content", android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)).apply { if (sha.isNotEmpty()) put("sha", sha) }
                    c.newCall(req(app, FILE).put(body.toString().toRequestBody(json)).build()).execute().use { r ->
                        if (r.code !in 200..201) error(when (r.code) { 401 -> "токен не подходит"; 403 -> "у токена нет права записи"; 404 -> "репозиторий не найден"; else -> "GitHub ответил ${r.code}" })
                    }
                }
                lastHash = text.hashCode()
                State("Сохранено в облако: маски, обход DPI, результаты, мини-нейросеть (" + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date()) + ")")
            }.getOrElse { State("Не сохранилось: ${Errors.human(it)}") }
        }
    }

    /** Downloads the backup and merges it in (this phone wins on conflicts). */
    fun restore(ctx: Context) {
        val app = ctx.applicationContext
        if (!configured(app) || state.value.busy) return
        state.value = State("Загружаю из облака…", true)
        scope.launch {
            state.value = runCatching {
                val text = call { c -> c.newCall(req(app, FILE).header("Accept", "application/vnd.github.raw+json").get().build()).execute().use { r ->
                    if (r.code == 404) error("в облаке ещё нет сохранения"); if (r.code != 200) error("GitHub ответил ${r.code}"); r.body!!.string() } }
                val msg = Actions.importBackup(text) ?: error("файл в облаке повреждён")
                text.lineSequence().firstOrNull { it.startsWith("brain:") }?.let { restoreBrain(app, it.removePrefix("brain:")) }
                State("Из облака: $msg")
            }.getOrElse { State("Не загрузилось: ${Errors.human(it)}") }
        }
    }

    private fun brain(ctx: Context) = "brain:" + ctx.getSharedPreferences("brain", 0).getString("w", "").orEmpty()
    private fun restoreBrain(ctx: Context, w: String) { if (w.isNotEmpty() && Brain.seen < 20) { ctx.getSharedPreferences("brain", 0).edit().putString("w", w).commit(); Brain.reload(ctx) } }

    /** From the autopilot: at most every 3 h, only when something changed. */
    fun auto(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAuto < 3 * 3600_000L || !configured(ctx)) return
        lastAuto = now; upload(ctx)
    }
}
