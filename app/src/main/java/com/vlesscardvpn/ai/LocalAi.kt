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
    private val history = mutableListOf<Pair<Boolean, String>>() // RAM only; never uploaded
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("local_chat", 0)
    private fun file(ctx: Context, m: LocalAiPolicy.Model) = File(ctx.filesDir, "local-chat/${m.id}.gguf")
    @Synchronized fun init(ctx: Context) {
        if (job?.isCompleted == false) return
        LocalAutoControl.init(ctx)
        LocalAiPolicy.models.forEach { File(file(ctx, it).path + ".part").delete() }
        val p = prefs(ctx); val m = LocalAiPolicy.model(p.getString("model", "qwen05") ?: "qwen05")
        state.value = State(p.getBoolean("enabled", false), m.id, downloaded = file(ctx, m).length() == m.bytes,
            message = if (p.getBoolean("enabled", false)) "Локальный чат · ${m.title}" else "Локальный чат выключен")
    }
    fun readyFor(ctx: Context, m: LocalAiPolicy.Model): Boolean {
        val memory = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        if (!energyReady(ctx)) return false
        return LocalAiPolicy.eligible(m, android.os.Process.is64Bit(), memory.totalMem, memory.availMem, memory.lowMemory)
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
        prefs(ctx).edit().putBoolean("enabled", enabled).apply(); init(ctx)
        if (enabled) { LocalAutoControl.enable(ctx, true); preparePolicy(ctx) }
        else LocalAutoControl.enable(ctx, false)
    }
    @Synchronized fun cancel() {
        job?.cancel()
        state.value = state.value.copy(busy = job?.isCompleted == false, message = "Остановлено; VPN продолжает работать")
    }
    @Synchronized fun delete(ctx: Context) {
        enable(ctx, false); val m = LocalAiPolicy.model(state.value.model)
        file(ctx, m).delete(); File(file(ctx, m).path + ".part").delete()
        com.vlesscardvpn.core.Assistant.messages.value = listOf(com.vlesscardvpn.core.Assistant.Msg(false,
            com.vlesscardvpn.core.Assistant.HELLO, com.vlesscardvpn.core.Assistant.START))
        state.value = state.value.copy(downloaded = false, message = "Модель удалена, история очищена")
    }
    @Synchronized fun download(ctx: Context) {
        if (job?.isCompleted == false) return
        val app = ctx.applicationContext; val model = LocalAiPolicy.model(state.value.model)
        if (!readyFor(app, model)) { state.value = state.value.copy(message = "Устройство сейчас не готово: проверьте 64-битный Android, ОЗУ, заряд, экономию энергии и температуру. Auto работает без LLM."); return }
        state.value = state.value.copy(busy = true, message = "Загрузка модели по вашему запросу…", progress = 0)
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val f = file(app, model); f.parentFile!!.mkdirs()
                    require(f.parentFile!!.usableSpace > model.bytes + 256L*1024*1024) { "disk" }
                    val temp = File(f.path + ".part")
                    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                        .callTimeout(60, TimeUnit.MINUTES).followSslRedirects(false).build()
                    val call = client.newCall(Request.Builder().url(model.url).build())
                    val cancelHook = coroutineContext.job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (it != null) call.cancel() }
                    try {
                        call.execute().use { r ->
                            require(r.isSuccessful && r.request.url.isHttps) { "download" }
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
            catch (_: Exception) { state.value = state.value.copy(message = "Загрузка не завершена: проверьте сеть, место и память. Непроверенная модель не используется.") }
            finally { state.value = state.value.copy(busy = false) }
        }
    }
    /** User-input actions remain separate from generated text. This never invokes Assistant.run. */
    @Synchronized fun ask(ctx: Context, question: String, facts: String, planner: Boolean = false, done: (LocalAiPolicy.Reply?) -> Unit) {
        if (job?.isCompleted == false) { done(LocalAiPolicy.Reply("Модель занята. Можно остановить её или использовать обычный Auto.", null)); return }
        val app = ctx.applicationContext; val m = LocalAiPolicy.model(state.value.model)
        if (!state.value.enabled || !state.value.downloaded || !readyFor(app, m)) { done(null); return }
        val ticket = LocalAutoControl.ticket()
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val sensorFacts = "battery=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)};charging=${bm.isCharging};saver=${pm.isPowerSaveMode};"
        val prompt = LocalAiPolicy.prompt(question, history.toList(), sensorFacts + facts)
        state.value = state.value.copy(busy = true, progress = -1, message = "Локальная генерация · VPN не ждёт модель")
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
                if (e is TimeoutCancellationException) done(null)
                else throw e
            } catch (_: Exception) { done(null) }
            finally { state.value = state.value.copy(busy = false) }
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
        lateinit var connection: ServiceConnection
        try {
            return suspendCancellableCoroutine { c ->
                val receiver = Messenger(Handler(Looper.getMainLooper()) { msg ->
                    if (c.isActive) {
                        val text = msg.data.getString("text")
                        if (text != null) c.resume(text) else c.resumeWithException(IllegalStateException("runtime_failed"))
                    }; true
                })
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (!c.isActive) return
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
        } finally { if (bound) runCatching { ctx.unbindService(connection) } }
    }
}
