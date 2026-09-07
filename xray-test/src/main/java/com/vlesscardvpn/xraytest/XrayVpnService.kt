package com.vlesscardvpn.xraytest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import go.Seq
import libv2ray.Libv2ray
import libv2ray.CoreController
import libv2ray.CoreCallbackHandler
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class XrayVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var run: Job? = null
    private var destroyed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            TestState.update(TestState.session.value.copy(message = "Останавливаю…", busy = true))
            if (run?.isCompleted == false) run?.cancel() else { TestState.update(Session()); stopSelf() }
            return START_NOT_STICKY
        }
        if (intent?.action != "START") { stopSelf(); return START_NOT_STICKY }
        if (run?.isCompleted == false) return START_NOT_STICKY
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("vpn-test", "Xray VPN test", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 2, Intent(this, XrayVpnService::class.java).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "vpn-test").setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("VLESS Card · Xray VPN").setContentText("VPN активен и защищает соединение").setContentIntent(open)
                .addAction(0, "Отключить", stop).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(42, notification)
        } catch (e: Exception) {
            Reports.error(e); TestState.update(Session(message = "Не удалось запустить VPN-сервис. Открой отчёт.")); stopSelf(); return START_NOT_STICKY
        }
        val nodes = TestState.profiles.value.toList()
        val auto = intent.getBooleanExtra("auto", false)
        val consent = intent.getBooleanExtra("consent", false)
        val selected = intent.getIntExtra("selected", 0)
        run = scope.launch { operate(nodes, auto, consent, selected, startId) }
        return START_NOT_STICKY
    }

    private suspend fun operate(nodes: List<Node>, auto: Boolean, consent: Boolean, selected: Int, startId: Int) {
        var tun: ParcelFileDescriptor? = null
        var controller: CoreController? = null
        var failure = false
        try {
            check(consent && nodes.isNotEmpty() && selected in nodes.indices) { "Invalid start request" }
            check(prepare(this) == null) { "VPN permission missing" }
            TestState.update(Session(busy = true, message = "Инициализация Xray…"))
            Reports.add("Xray environment init")
            Seq.setContext(applicationContext)
            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(code: Long, message: String?): Long = 0
            })
            currentCoroutineContext().ensureActive()
            Reports.add("Establish TUN")
            tun = Builder().setSession("VLESS Card VPN · Xray").setMtu(1400)
                .addAddress("172.19.0.1", 30).addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0)
                .addDnsServer("1.1.1.1").addDisallowedApplication(packageName)
                .setBlocking(false).establish() ?: error("TUN unavailable")

            var first = selected
            while (currentCoroutineContext().isActive) {
                val order = if (auto) nodes.indices.map { (first + it) % nodes.size } else listOf(selected)
                var connected = -1
                var port = 0
                for (i in order) {
                    currentCoroutineContext().ensureActive()
                    controller.stopLoop()
                    TestState.update(Session(busy = true, message = "Сервер ${i + 1}: запуск и проверка…", node = i))
                    Reports.add("Start candidate ${i + 1}")
                    port = ServerSocket(0).use { it.localPort }
                    controller.startLoop(XrayConfig.build(nodes[i], port), tun.fd)
                    check(controller.isRunning) { "Core did not start" }
                    currentCoroutineContext().ensureActive()
                    val results = checkServicesFast(port)
                    if (AutoPolicy.eligible(results)) { connected = i; break }
                    Reports.add("Candidate ${i + 1}: probe failed")
                    if (!auto) { connected = i; break }
                }
                if (connected < 0) {
                    failure = true
                    TestState.update(Session(message = "Авто: ни один сервер не прошёл быстрый тест связи."))
                    return
                }
                var failedRounds = 0
                val startedAt = android.os.SystemClock.elapsedRealtime()
                while (currentCoroutineContext().isActive) {
                    val results = checkServicesFast(port)
                    val healthy = AutoPolicy.eligible(results)
                    failedRounds = if (healthy) 0 else failedRounds + 1
                    val description = "Сервер ${connected + 1} · Telegram ${if (results[0]) "✓" else "✗"} · YouTube ${if (results[1]) "✓" else "✗"}"
                    TestState.update(Session(active = true, message = (if (healthy) "Туннель активен. " else "Туннель запущен, есть ошибки HTTPS. ") + description, node = connected))
                    if (AutoPolicy.shouldSwitch(auto, failedRounds, android.os.SystemClock.elapsedRealtime() - startedAt)) {
                        Reports.add("Auto: 2 consecutive failures, switching candidate")
                        first = (connected + 1) % nodes.size
                        break
                    }
                    delay(15000)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            failure = true; Reports.error(e); TestState.update(Session(message = "Ошибка запуска или проверки. Открой «Отчёт»."))
        } catch (e: LinkageError) {
            failure = true; Reports.error(e); TestState.update(Session(message = "Не удалось загрузить Xray на этом устройстве."))
        } finally {
            withContext(NonCancellable) {
                Reports.add("Stop core, then close TUN")
                try { controller?.stopLoop() } catch (e: Exception) { Reports.error(e) }
                try { tun?.close() } catch (e: Exception) { Reports.error(e) }
                withContext(Dispatchers.Main) {
                    if (!failure) TestState.update(Session())
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    if (destroyed) scope.cancel()
                }
            }
        }
    }

    private suspend fun checkServicesFast(port: Int): List<Boolean> = coroutineScope {
        val targets = listOf("https://telegram.org/" to 200, "https://www.youtube.com/generate_204" to 204)
        targets.mapIndexed { index, (url, expected) -> async {
            val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                .connectTimeout(2500, TimeUnit.MILLISECONDS).readTimeout(2500, TimeUnit.MILLISECONDS).callTimeout(3500, TimeUnit.MILLISECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
            try {
                val samples = mutableListOf<Long>()
                repeat(2) {
                    currentCoroutineContext().ensureActive()
                    val request = Request.Builder().url(url).header("Cache-Control", "no-store").apply { if (index == 0) head() }.build()
                    val begin = System.nanoTime()
                    val ok = awaitResponse(client.newCall(request), expected)
                    if (ok) samples.add((System.nanoTime() - begin) / 1000000)
                }
                val sorted = samples.sorted()
                val median = if (sorted.isEmpty()) -1L else sorted[0]
                Reports.add("${if (index == 0) "Telegram" else "YouTube"}: ${samples.size}/2, median=$median ms")
                samples.isNotEmpty()
            } finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        } }.awaitAll()
    }

    private suspend fun awaitResponse(call: Call, expected: Int): Boolean = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { continuation.resume(false) }
            override fun onResponse(call: Call, response: Response) { val ok = response.use { it.code == expected }; continuation.resume(ok) }
        })
    }

    override fun onRevoke() { run?.cancel(); super.onRevoke(); stopSelf() }
    override fun onDestroy() {
        destroyed = true
        if (run?.isCompleted == false) run?.cancel() else scope.cancel()
        super.onDestroy()
    }
}
