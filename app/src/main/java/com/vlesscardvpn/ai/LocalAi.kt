package com.vlesscardvpn.ai

import android.app.ActivityManager
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
object LocalAi {
    data class State(val enabled: Boolean = false, val model: String = "qwen05", val busy: Boolean = false,
        val downloaded: Boolean = false, val progress: Int = -1, val message: String = "Локальный чат выключен")
    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var job: Job? = null
    private var preparingBundle = false
    private var planning = false
    private var dependentJob: Job? = null
    private val history = mutableListOf<Pair<Boolean, String>>() // RAM only; never uploaded
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("local_chat", 0)
    private fun file(ctx: Context, m: LocalAiPolicy.Model) = File(ctx.filesDir, "local-chat/${m.id}.gguf")
    @Synchronized fun init(ctx: Context) {
        if (job?.isCompleted == false) return
        LocalAutoControl.init(ctx)
        LocalAiPolicy.models.forEach { File(file(ctx, it).path + ".part").delete() }
        val p = prefs(ctx)
        val bundled = com.vlesscardvpn.BuildConfig.BUNDLED_AI
        val firstBundle = bundled && !p.getBoolean("bundle_setup_170", false)
        val m = if (firstBundle) LocalAiPolicy.compact else LocalAiPolicy.model(p.getString("model", "qwen05") ?: "qwen05")
        val exists = file(ctx, m).length() == m.bytes
        val enabled = if (firstBundle) true else p.getBoolean("enabled", bundled)
        state.value = State(enabled, m.id, downloaded = exists,
            message = when { !enabled -> "Локальный чат выключен"; !exists -> "Подготовка встроенной модели"; else -> blockReason(ctx, m) ?: "Локальный чат готов · ${m.title}" })
        if (bundled && m == LocalAiPolicy.compact && !exists && !p.getBoolean("bundle_opt_out", false)) installBundle(ctx)
        else if (firstBundle && exists) p.edit().putString("model", m.id).putBoolean("enabled", true).putBoolean("bundle_setup_170", true).apply()
    }
    fun blockReason(ctx: Context, m: LocalAiPolicy.Model): String? {
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val p = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val b = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return when {
            !android.os.Process.is64Bit() -> "Для локальной модели нужен 64-битный Android. VPN и обычный Auto доступны."
            info.totalMem < m.minRam -> "Недостаточно общей ОЗУ для ${m.title}. Нужна компактная модель или более мощное устройство."
            info.lowMemory || info.availMem < m.minAvailable -> "Недостаточно свободной ОЗУ: сейчас ${info.availMem / 1048576} МиБ, нужно ${m.minAvailable / 1048576} МиБ. Закройте тяжёлые приложения."
            !p.isInteractive -> "Экран выключен: генерация приостановлена."
            p.isPowerSaveMode -> "Генерация приостановлена системной экономией энергии."
            !b.isCharging && b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) !in 21..100 -> "Для генерации зарядите телефон выше 20% или подключите зарядку."
            com.vlesscardvpn.core.Thermal.hot(com.vlesscardvpn.core.Thermal.tempC(ctx), com.vlesscardvpn.core.Thermal.status(ctx), false) -> "Телефон нагрет: генерация возобновится после остывания."
            else -> null
        }
    }
    fun readyFor(ctx: Context, m: LocalAiPolicy.Model): Boolean = blockReason(ctx, m) == null

    @Synchronized private fun installBundle(ctx: Context) {
        if (job?.isCompleted == false) return
        if (!android.os.Process.is64Bit()) { state.value = state.value.copy(message = "Встроенная модель требует 64-битный Android. Обычный Auto доступен."); return }
        val app = ctx.applicationContext; val model = LocalAiPolicy.compact
        preparingBundle = true
        state.value = state.value.copy(model = model.id, enabled = true, busy = true, progress = 0,
            message = "Копирую модель из APK. Интернет не нужен…")
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val target = file(app, model); target.parentFile!!.mkdirs()
                    require(target.parentFile!!.usableSpace > model.bytes + 64L*1024*1024) { "disk" }
                    val temp = File(target.path + ".part")
                    try {
                        val sha = MessageDigest.getInstance("SHA-256"); var count = 0L
                        app.assets.open("models/qwen05.gguf").use { input -> temp.outputStream().use { output ->
                            val buffer = ByteArray(64*1024)
                            while (true) {
                                ensureActive(); val n = input.read(buffer); if (n < 0) break
                                count += n; require(count <= model.bytes) { "size" }
                                output.write(buffer, 0, n); sha.update(buffer, 0, n)
                                state.value = state.value.copy(progress = (count*100/model.bytes).toInt())
                            }
                            output.fd.sync()
                        } }
                        require(count == model.bytes && sha.digest().joinToString("") { "%02x".format(it) } == model.sha256) { "checksum" }
                        ensureActive(); require(temp.renameTo(target)) { "rename" }
                    } finally { temp.delete() }
                }
                prefs(app).edit().putString("model", model.id).putBoolean("enabled", true)
                    .putBoolean("bundle_setup_170", true).putBoolean("bundle_opt_out", false).apply()
                state.value = state.value.copy(enabled = true, downloaded = true, message = blockReason(app, model) ?: "Модель установлена из APK. Можно писать в чат.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                state.value = state.value.copy(message = if (e.message == "disk") "Нужно ещё около 550 МБ свободного места для распаковки модели." else "Не удалось подготовить модель из APK. Повторите подготовку; интернет не требуется.")
            } finally { preparingBundle = false; state.value = state.value.copy(busy = false, progress = -1) }
        }
    }
    fun energyReady(ctx: Context): Boolean {
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val battery = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return LocalAiPolicy.energyAllowsInference(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            battery.isCharging, power.isPowerSaveMode, power.isInteractive) &&
            !com.vlesscardvpn.core.Thermal.hot(com.vlesscardvpn.core.Thermal.tempC(ctx), com.vlesscardvpn.core.Thermal.status(ctx), false)
    }
    @Synchronized fun select(ctx: Context, id: String) {
        cancel(); history.clear(); LocalAutoControl.invalidate()
        prefs(ctx).edit().putString("model", LocalAiPolicy.model(id).id).putBoolean("enabled", false).apply(); init(ctx)
    }
    @Synchronized fun enable(ctx: Context, enabled: Boolean) {
        cancel(); history.clear(); LocalAutoControl.invalidate()
        prefs(ctx).edit().putBoolean("enabled", enabled).putBoolean("bundle_opt_out", !enabled).apply()
        state.value = state.value.copy(enabled = enabled); init(ctx)
        if (enabled) { LocalAutoControl.enable(ctx, true) }
        else LocalAutoControl.enable(ctx, false)
    }
    @Synchronized fun cancel() {
        job?.cancel(); dependentJob?.cancel(); dependentJob = null
        state.value = state.value.copy(busy = job?.isCompleted == false, message = "Остановлено; VPN продолжает работать")
    }
    @Synchronized fun delete(ctx: Context) {
        prefs(ctx).edit().putBoolean("bundle_opt_out", true).apply()
        enable(ctx, false); val m = LocalAiPolicy.model(state.value.model)
        file(ctx, m).delete(); File(file(ctx, m).path + ".part").delete()
        com.vlesscardvpn.core.Assistant.messages.value = listOf(com.vlesscardvpn.core.Assistant.Msg(false,
            com.vlesscardvpn.core.Assistant.HELLO, com.vlesscardvpn.core.Assistant.START))
        state.value = state.value.copy(downloaded = false, message = "Модель удалена, история очищена")
    }
    @Synchronized fun download(ctx: Context) {
        if (job?.isCompleted == false) return
        val app = ctx.applicationContext; val model = LocalAiPolicy.model(state.value.model)
        if (model == LocalAiPolicy.compact && com.vlesscardvpn.BuildConfig.BUNDLED_AI) {
            prefs(app).edit().putBoolean("bundle_opt_out", false).apply(); installBundle(app); return
        }
        // File preparation does not require inference-level battery/RAM conditions.
        if (!android.os.Process.is64Bit()) { state.value = state.value.copy(message = "Для локальной модели нужен 64-битный Android."); return }
        state.value = state.value.copy(busy = true, message = "Загрузка модели по вашему запросу…", progress = 0)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val f = file(app, model); f.parentFile!!.mkdirs()
                    require(f.parentFile!!.usableSpace > model.bytes + 256L*1024*1024) { "disk" }
                    val temp = File(f.path + ".part")
                    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                        .callTimeout(60, TimeUnit.MINUTES).followSslRedirects(false).build()
                    val call = client.newCall(Request.Builder().url(model.url + "?download=true&request=" + System.currentTimeMillis()).build())
                    val cancelHook = coroutineContext.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
                    try {
                        call.execute().use { r ->
                            require(r.isSuccessful && r.request.url.isHttps) { "http_${r.code}" }
                            val body = r.body ?: error("empty")
                            val declared = body.contentLength(); require(declared == -1L || declared == model.bytes) { "length" }
                            val sha = MessageDigest.getInstance("SHA-256"); var count = 0L
                            body.byteStream().use { input -> temp.outputStream().use { output ->
                                val buffer = ByteArray(64*1024)
                                while (true) {
                                    ensureActive(); val n = input.read(buffer); if (n < 0) break
                                    count += n; require(count <= model.bytes) { "oversize" }
                                    output.write(buffer, 0, n); sha.update(buffer, 0, n)
                                    state.value = state.value.copy(progress = (count*100/model.bytes).toInt())
                                }
                                output.fd.sync()
                            } }
                            require(count == model.bytes && sha.digest().joinToString("") { "%02x".format(it) } == model.sha256) { "checksum" }
                            ensureActive(); require(temp.renameTo(f)) { "rename" }
                        }
                    } finally { cancelHook.dispose(); call.cancel(); temp.delete(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                }
                state.value = state.value.copy(downloaded = true, message = "Модель проверена по SHA-256. Теперь можно включить локальный чат.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = state.value.copy(message = when {
                e.message == "disk" -> "Недостаточно места для выбранной модели."
                e.message?.matches(Regex("http_[0-9]{3}")) == true -> "Сервер модели вернул HTTP " + e.message!!.removePrefix("http_") + ". Встроенная компактная модель не требует этой загрузки."
                else -> "Загрузка не завершена: проверьте сеть и место. Непроверенная модель не используется."
            }) }
            finally { state.value = state.value.copy(busy = false) }
        }
    }
    /** User-input actions remain separate from generated text. This never invokes Assistant.run. */
    @Synchronized fun ask(ctx: Context, question: String, facts: String, planner: Boolean = false, done: (LocalAiPolicy.Reply?) -> Unit) {
        if (job?.isCompleted == false) {
            if (!planner && (planning || preparingBundle)) {
                val previous = job!!
                dependentJob = previous
                if (planning) previous.cancel()
                job = scope.launch {
                    try { previous.join(); ensureActive(); dependentJob = null; job = null; ask(ctx, question, facts, false, done) }
                    finally { if (!isActive) previous.cancel() }
                }
            } else done(LocalAiPolicy.Reply("Предыдущий ответ ещё готовится. Можно остановить модель.", null))
            return
        }
        val app = ctx.applicationContext; val m = LocalAiPolicy.model(state.value.model)
        val blocked = when {
            !state.value.enabled -> "Локальный чат выключен. Включите его в панели «Локальный интеллект»."
            !state.value.downloaded -> "Модель ещё не подготовлена. Откройте панель «Локальный интеллект» и нажмите «Подготовить из APK»."
            else -> blockReason(app, m)
        }
        if (blocked != null) { state.value = state.value.copy(message = blocked); done(LocalAiPolicy.Reply(blocked, null)); return }
        val ticket = LocalAutoControl.ticket()
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val sensorFacts = "battery=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)};charging=${bm.isCharging};saver=${pm.isPowerSaveMode};"
        val prompt = LocalAiPolicy.prompt(question, history.toList(), sensorFacts + facts)
        state.value = state.value.copy(busy = true, progress = -1, message = "Локальная генерация · VPN не ждёт модель")
        planning = planner
        job = scope.launch {
            try {
                val raw = withTimeout(45000) { infer(app, m.id, prompt, planner) }
                ensureActive()
                val parsed = LocalAiPolicy.parse(raw)
                val reply = if (LocalAutoControl.current(ticket)) parsed else parsed.copy(command = null, plan = null, energy = null)
                LocalAutoControl.accept(ticket, reply.plan, reply.energy)
                history.add(true to question); history.add(false to reply.text)
                while (history.size > 6) history.removeAt(0)
                state.value = state.value.copy(message = "Ответ сгенерирован локально. Это не результат сетевой проверки.")
                done(reply)
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) done(LocalAiPolicy.Reply("Модель не успела ответить за 45 секунд. Попробуйте короткий вопрос; Auto работает независимо.", null))
                else throw e
            } catch (e: Exception) {
                val message = when (e.message) {
                    "context_limit" -> "Слишком большой контекст. Задайте более короткий вопрос."
                    "model_load", "context_init" -> "Не удалось загрузить модель в память. Освободите ОЗУ и повторите."
                    "energy_limit" -> "Генерация остановлена энергозащитой: проверьте заряд, температуру и режим экономии."
                    "runtime_died" -> "Процесс модели остановлен Android или watchdog. Попробуйте освободить память."
                    "generation_timeout", "prefill_timeout" -> "Модель не уложилась в лимит времени. Попробуйте короткий вопрос."
                    else -> blockReason(app, m) ?: ("Не удалось запустить локальный inference. Код: " + (e.message?.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "runtime_failed"))
                }
                state.value = state.value.copy(message = message); done(LocalAiPolicy.Reply(message, null))
            } finally { planning = false; state.value = state.value.copy(busy = false) }
        }
    }
    private var lastPlanRequest = -300000L
    @Synchronized fun preparePolicy(ctx: Context) {
        init(ctx)
        if (!LocalAutoControl.enabled || !state.value.enabled || state.value.busy ||
            SystemClock.elapsedRealtime() - lastPlanRequest < 300000) return
        lastPlanRequest = SystemClock.elapsedRealtime()
        // Fire-and-forget: never waits inside the Fast Auto budget or adds network probes.
        val s = com.vlesscardvpn.core.Tunnel.status.value
        ask(ctx, "Выбери план Auto: низкая задержка или стабильность. Закончи PLAN=latency либо PLAN=stability и POWER=eco для экономии заряда и ACTION=none.",
            "connected=${s.state == com.vlesscardvpn.core.Tunnel.State.CONNECTED};data=${s.checkOk}", planner = true) { }
    }
    private suspend fun infer(ctx: Context, id: String, prompt: String, planner: Boolean): String {
        var bound = false
        var remote: IBinder? = null
        val died = CompletableDeferred<Unit>()
        val death = IBinder.DeathRecipient { died.complete(Unit) }
        lateinit var connection: ServiceConnection
        try {
            return suspendCancellableCoroutine { c ->
                val receiver = Messenger(Handler(Looper.getMainLooper()) { msg ->
                    if (c.isActive) {
                        val text = msg.data.getString("text")
                        if (text != null) c.resume(text) else c.resumeWithException(IllegalStateException(msg.data.getString("error") ?: "runtime_failed"))
                    }; true
                })
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (!c.isActive) return
                        remote = service
                        runCatching { service.linkToDeath(death, 0) }
                        runCatching { Messenger(service).send(Message.obtain(null, 1).apply {
                            replyTo = receiver; data = Bundle().apply { putString("model", id); putString("prompt", prompt); putBoolean("planner", planner) }
                        }) }.onFailure { if (c.isActive) c.resumeWithException(it) }
                    }
                    override fun onServiceDisconnected(name: ComponentName) { if (c.isActive) c.resumeWithException(IllegalStateException("runtime_died")) }
                    override fun onNullBinding(name: ComponentName) { if (c.isActive) c.resumeWithException(IllegalStateException("null_binding")) }
                    override fun onBindingDied(name: ComponentName) { if (c.isActive) c.resumeWithException(IllegalStateException("binding_died")) }
                }
                bound = ctx.bindService(Intent(ctx, LocalAiService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound && c.isActive) c.resumeWithException(IllegalStateException("bind_failed"))
            }
        } finally {
            withContext(NonCancellable) {
                remote?.let { binder -> runCatching { Messenger(binder).send(Message.obtain(null, 3)) } }
                if (bound) runCatching { ctx.unbindService(connection) }
                if (remote != null) withTimeoutOrNull(1500) { died.await() }
                remote?.let { runCatching { it.unlinkToDeath(death, 0) } }
            }
        }
    }
}
