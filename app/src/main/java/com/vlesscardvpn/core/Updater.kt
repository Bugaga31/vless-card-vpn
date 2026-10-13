package com.vlesscardvpn.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * «Обновления внутри приложения»: looks at the latest GitHub release, downloads the APK for this phone (arm64 or
 * universal) and hands it to Android's installer — no browser, no GitHub page. Android itself asks «Установить?»
 * (an app may never install silently). Downloads directly, and through the running VPN if GitHub is slow/blocked.
 */
object Updater {
    const val REPO = "Bugaga31/vless-card-vpn"
    data class State(val latest: String = "", val code: Int = 0, val notes: String = "", val busy: Boolean = false, val text: String = "")
    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastCheck = 0L
    private var lastAttempt = 0L
    @Volatile private var checking = false
    private val downloadLock = Mutex()
    private var noted = ""

    /** "v1.1.4" → 105, "v1.0.99" → 99 (same numbering as the build: 1.1.x = 101 + x). */
    fun codeOf(tag: String): Int = UpdatePolicy.codeOf(tag)

    fun apkName(tag: String, arm64: Boolean) = "VLESS-Card-Beta-${tag.removePrefix("v")}" + (if (arm64) "-arm64" else "") + ".apk"

    private fun client(viaVpn: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).followRedirects(true)
        if (viaVpn) Tunnel.socks?.port?.let { b.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it))) }
        return b.build()
    }

    private fun <T> anyPath(block: (OkHttpClient) -> T): T =
        try { block(client(false)) } catch (e: Exception) { if (e is CancellationException) throw e; if (Tunnel.socks != null && e is java.io.IOException) block(client(true)) else throw e }

    /** Checks at most every 6 h unless [force]. */
    @Synchronized fun check(force: Boolean = false, ctx: Context? = null) {
        val now = System.currentTimeMillis()
        if (checking || state.value.busy || !force && !UpdatePolicy.checkDue(now, lastCheck, lastAttempt)) return
        checking = true; lastAttempt = now
        scope.launch {
            if (force) state.value = state.value.copy(text = "Проверяю обновления…")
            runCatching {
                val body = anyPath { c -> c.newCall(Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("Accept", "application/vnd.github+json").build()).execute().use { r ->
                        if (!r.isSuccessful) throw java.io.IOException("GitHub ответил ${r.code}")
                        String(r.body!!.byteStream().use { BoundedInput.read(it, 512 * 1024) }) } }
                val o = org.json.JSONObject(body)
                val tag = o.getString("tag_name")
                val code = codeOf(tag)
                require(code > 0 && !o.optBoolean("draft") && !o.optBoolean("prerelease")) { "Некорректная версия релиза" }
                lastCheck = now
                val mine = com.vlesscardvpn.BuildConfig.VERSION_CODE
                state.value = if (code > mine) State(tag.removePrefix("v"), code, o.optString("body").substringBefore("### Автоматические").trim().take(1500), text = "Доступна версия ${tag.removePrefix("v")}")
                    else State(text = if (force) "У вас последняя версия (${com.vlesscardvpn.BuildConfig.VERSION_NAME})" else "")
                state.value.let { st -> if (st.latest.isNotEmpty()) { ctx?.let { prefetch(it.applicationContext) }; if (!force && st.latest != noted) { noted = st.latest; AppWatch.note("Вышла версия ${st.latest} — обновить можно на главной одной кнопкой") } } }
            }.onFailure { if (force) state.value = state.value.copy(text = "Не удалось проверить: ${Errors.human(it)}") }
            checking = false
        }
    }

    @Volatile private var ready: String = ""
    private var readyDigest = ""

    @Suppress("DEPRECATION") private fun signers(info: android.content.pm.PackageInfo): Set<String> {
        val certs = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return certs.orEmpty().map { UpdatePolicy.hash(it.toByteArray().inputStream()) }.toSet()
    }
    @Suppress("DEPRECATION") private fun validateArchive(app: Context, f: java.io.File, s: State) {
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
        val candidate = app.packageManager.getPackageArchiveInfo(f.absolutePath, flags) ?: error("Файл не является корректным APK")
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        require(UpdatePolicy.acceptsArchive(app.packageName, s.code, com.vlesscardvpn.BuildConfig.VERSION_CODE, candidate.packageName, code,
            signers(installed), signers(candidate))) { "Пакет, версия или сертификат обновления не совпадают — установка остановлена" }
    }
    private suspend fun download(app: Context, s: State): java.io.File = downloadLock.withLock {
        val job = currentCoroutineContext()
        val f = java.io.File(app.cacheDir, "update.apk")
        val tmp = java.io.File(app.cacheDir, "update.part.apk")
        if (ready == s.latest && f.isFile && readyDigest.isNotEmpty()) {
            val valid = f.length() in 1..UpdatePolicy.MAX_APK && runCatching { validateArchive(app, f, s); f.inputStream().use { UpdatePolicy.hash(it) } == readyDigest }.getOrDefault(false)
            if (valid) return@withLock f
            f.delete(); ready = ""; readyDigest = ""
        }
        val arm64 = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        val tag = "v" + s.latest
        require(codeOf(tag) == s.code && s.code > com.vlesscardvpn.BuildConfig.VERSION_CODE)
        val name = apkName(tag, arm64)
        val base = "https://github.com/$REPO/releases/download/$tag/"
        val manifest = anyPath { c -> c.newCall(Request.Builder().url(base + "SHA256SUMS-Beta.txt").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Нет контрольных сумм: ${r.code}")
            String(r.body!!.byteStream().use { BoundedInput.read(it, 64 * 1024) }) } }
        val sha = UpdatePolicy.checksum(manifest, name)
        try {
            anyPath { c ->
                c.newCall(Request.Builder().url(base + name).build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("GitHub ответил ${r.code}")
                    val body = r.body ?: error("Пустой ответ загрузки")
                    val total = body.contentLength(); var last = 0L
                    body.byteStream().use { inp -> tmp.outputStream().use { out ->
                        UpdatePolicy.copyVerified(inp, out, sha, total) { done ->
                            job.ensureActive()
                            if (done - last > 1_000_000 && state.value.busy && state.value.latest == s.latest) {
                                last = done; state.value = state.value.copy(text = "Скачиваю ${s.latest}: ${done shr 20} из ${if (total > 0) total shr 20 else "?"} МБ")
                            }
                        }
                    } }
                }
            }
            job.ensureActive(); validateArchive(app, tmp, s)
            check(tmp.renameTo(f)) { "Не удалось сохранить проверенное обновление" }
            ready = s.latest; readyDigest = sha
            f
        } catch (e: Exception) { tmp.delete(); throw e }
    }

    /** On Wi-Fi the new version is downloaded in the background, so «Обновить» installs at once. */
    private fun prefetch(app: Context) {
        val s = state.value
        if (s.latest.isEmpty() || s.busy || ready == s.latest) return
        val unmetered = runCatching {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        }.getOrDefault(false)
        if (!unmetered) return
        scope.launch { runCatching { download(app, s) }.onSuccess { if (state.value.latest == s.latest && !state.value.busy) state.value = state.value.copy(text = "Версия ${s.latest} скачана — нажмите «Обновить», установка займёт секунды") } }
    }

    /** Downloads the update (if not yet) and opens Android's «Установить?». */
    fun install(ctx: Context) {
        val s = state.value
        if (s.latest.isEmpty() || s.busy) return
        val app = ctx.applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            state.value = s.copy(text = "Разрешите VLESS Card устанавливать обновления (один раз) и нажмите «Обновить» снова")
            runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + app.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        state.value = s.copy(busy = true, text = "Скачиваю ${s.latest}…")
        scope.launch {
            var sessionId: Int? = null
            try {
                val f = download(app, s)
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(app.packageName) }
                val id = pi.createSession(params); sessionId = id
                pi.openSession(id).use { session ->
                    session.openWrite("update.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val cb = PendingIntent.getBroadcast(app, id, Intent(app, InstallReceiver::class.java), flags)
                    session.commit(cb.intentSender); sessionId = null
                }
                state.value = state.value.copy(busy = false, text = "Подтвердите установку ${s.latest} в окне Android")
            } catch (e: Exception) {
                sessionId?.let { runCatching { app.packageManager.packageInstaller.abandonSession(it) } }
                ready = ""; readyDigest = ""
                state.value = state.value.copy(busy = false, text = "Не получилось обновить: ${Errors.human(e)}. Можно скачать вручную на GitHub")
            }
        }
    }
}

/** Android's answer about the update: when it needs the user's «Установить», show its window. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = Updater.State(text = "Обновлено")
            else -> Updater.state.value = Updater.state.value.copy(busy = false, text = "Установка не прошла: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "отменено"))
        }
    }
}
