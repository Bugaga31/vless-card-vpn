package com.vlesscardvpn.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vlesscardvpn.MainActivity
import com.vlesscardvpn.core.LibboxPlatformInterface
import com.vlesscardvpn.core.NativeCallbackValues
import com.vlesscardvpn.core.NetworkProfileManager
import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.core.CrashReportManager
import com.vlesscardvpn.VlessApplication
import go.Seq

import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.domain.AutoConnectPolicy
import com.vlesscardvpn.domain.LocalProbeProxy
import com.vlesscardvpn.domain.TunnelHealthChecker
import com.vlesscardvpn.domain.TunnelHealthReport
import com.vlesscardvpn.data.PublicConfigFetcher
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class VpnStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    STOPPING,
    ERROR
}

data class VpnSessionStats(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val activeConfig: VlessConfig? = null,
    val connectedSinceTimestamp: Long = 0L,
    val durationSeconds: Long = 0L,
    val bytesIn: Long = 0L,
    val bytesOut: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val errorMessage: String? = null,
    val progressMessage: String = "",
    val autoMode: Boolean = false,
    val health: TunnelHealthReport = TunnelHealthReport(),
    val profileLabel: String = "Параметры сервера"
)

class VlessVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.vlesscardvpn.CONNECT"
        const val ACTION_AUTO = "com.vlesscardvpn.AUTO"
        const val ACTION_DISCONNECT = "com.vlesscardvpn.DISCONNECT"
        const val EXTRA_CONFIG_ID = "config_id"

        private val _vpnStats = MutableStateFlow(VpnSessionStats())
        val vpnStats = _vpnStats.asStateFlow()

        fun startVpn(context: Context, config: VlessConfig) {
            try {
                val intent = Intent(context, VlessVpnService::class.java).apply {
                    action = ACTION_CONNECT
                    putExtra(EXTRA_CONFIG_ID, config.id)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("VlessVpnService", "startVpn failed", e)
                _vpnStats.value = VpnSessionStats(
                    status = VpnStatus.ERROR,
                    activeConfig = config,
                    errorMessage = "Не удалось запустить службу VPN: ${e.localizedMessage ?: "Отказано в доступе"}"
                )
            }
        }

        fun startAuto(context: Context, recovery: Boolean = false) {
            val intent = Intent(context, VlessVpnService::class.java).apply {
                action = ACTION_AUTO; putExtra("auto_recovery", recovery)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) {
                _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR,
                    errorMessage = "Не удалось запустить автоматический подбор")
            }
        }

        fun stopVpn(context: Context) {
            val intent = Intent(context, VlessVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private var probeProxy: LocalProbeProxy? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private val backgroundErrors: CoroutineExceptionHandler = CoroutineExceptionHandler { _, failure ->
        CrashReportManager.recordException(applicationContext, Thread.currentThread(), failure, "VPN_BACKGROUND")
        sessionSequence.incrementAndGet()
        connectionJob?.cancel()
        serviceScope.launch {
            operationMutex.withLock {
                cleanupResources()
                _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR,
                    errorMessage = "Ошибка фоновой задачи. Откройте отчёты об ошибках.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + backgroundErrors)
    private var nativeInitialized = false
    private val operationMutex = Mutex()
    private val sessionSequence = AtomicLong(0L)

    private var connectionJob: Job? = null
    private var statsJob: Job? = null
    private var healthJob: Job? = null
    private var autoRecoveryAttempts = 0
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var platformAdapter: LibboxPlatformInterface? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    @Volatile private var isScreenInteractive = true

    private lateinit var repository: AppRepository

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(applicationContext)
        createNotificationChannel()
        registerScreenStateReceiver()
    }

    private fun registerScreenStateReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            isScreenInteractive = false
                            Log.d("VlessVpnService", "Screen OFF: entering energy-saving idle state")
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            isScreenInteractive = true
                            Log.d("VlessVpnService", "Screen ON: returning to active state")
                        }
                    }
                }
            }
            screenStateReceiver = receiver
            registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w("VlessVpnService", "Failed to register screen state receiver", e)
        }
    }

    /** Run on Dispatchers.IO only after startForeground has succeeded. */
    private fun initLibboxEnvironment() {
        if (nativeInitialized) return
        Seq.setContext(applicationContext)
        val options = SetupOptions().apply {
            basePath = File(filesDir, "singbox").apply { mkdirs() }.absolutePath
            workingPath = File(cacheDir, "singbox_work").apply { mkdirs() }.absolutePath
            tempPath = File(cacheDir, "singbox_temp").apply { mkdirs() }.absolutePath
            fixAndroidStack = true
            debug = false
        }
        Libbox.setup(options)
        nativeInitialized = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            serviceScope.launch {
                operationMutex.withLock {
                    if (_vpnStats.value.status != VpnStatus.CONNECTED && _vpnStats.value.status != VpnStatus.CONNECTING) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_CONNECT, ACTION_AUTO -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID) ?: ""
                if (intent.action == ACTION_AUTO && !intent.getBooleanExtra("auto_recovery", false)) autoRecoveryAttempts = 0
                val sessionId = sessionSequence.incrementAndGet()

                // CRITICAL FIX: MUST call startForeground synchronously (Android 12+ / 14+ FGS rules)
                // with explicit FOREGROUND_SERVICE_TYPE_SPECIAL_USE before ANY coroutine launch or heavy work.
                if (!safeStartForeground(1, createNotification(null, "Подключение…"))) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                connectionJob?.cancel()
                connectionJob = serviceScope.launch {
                    if (intent.action == ACTION_AUTO) {
                        operationMutex.withLock { handleAutoConnectLocked(sessionId) }
                        if (_vpnStats.value.status == VpnStatus.ERROR) stopSelf()
                    } else handleConnect(configId, sessionId)
                }
            }
            ACTION_DISCONNECT -> {
                sessionSequence.incrementAndGet()
                connectionJob?.cancel()
                serviceScope.launch {
                    handleDisconnect()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun handleConnect(configId: String, sessionId: Long) {
        operationMutex.withLock { handleConnectLocked(configId, sessionId) }
        // A terminal failure must not leave a lingering (START_STICKY) background service.
        if (_vpnStats.value.status == VpnStatus.ERROR) {
            stopSelf()
        }
    }

    /** Explicit foreground Auto action: bounded, cancellable selection using real authenticated tunnels. */
    private suspend fun handleAutoConnectLocked(sessionId: Long) {
        if (sessionId != sessionSequence.get()) return
        cleanupResources()
        _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true,
            progressMessage = "Авто: проверяем сохранённые серверы")
        val settings = repository.settingsFlow.value
        val identities = mutableSetOf<String>()
        var attempts = 0
        var lastReport = TunnelHealthReport()
        suspend fun tryPool(pool: List<VlessConfig>, limit: Int = AutoConnectPolicy.MAX_ATTEMPTS): Boolean {
            val candidates = AutoConnectPolicy.rank(pool, settings.autopilotAllowedOnlyFavorites).take(24)
            // A TCP probe only orders candidates; it never marks a VPN healthy.
            val reachable = coroutineScope {
                candidates.chunked(8).flatMap { group ->
                    group.map { c -> async { c to PingTester.pingConfig(c, 1200) } }.awaitAll()
                }
            }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<VlessConfig, Int>> { it.first.isFavorite }
                .thenBy { it.second })
            for ((config, ping) in reachable) {
                currentCoroutineContext().ensureActive()
                if (sessionId != sessionSequence.get() || attempts >= limit) return false
                if (!identities.add(AutoConnectPolicy.identity(config))) continue
                repository.addConfig(config.copy(pingMs = ping, healthState = "UNKNOWN"))
                for (fragment in listOf(false, true)) {
                    if (fragment && !AutoConnectPolicy.canFragment(config)) continue
                    if (attempts >= limit) return false
                    attempts++
                    _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true,
                        activeConfig = config, progressMessage = "Авто: маршрут $attempts/${AutoConnectPolicy.MAX_ATTEMPTS}")
                    safeStartForeground(1, createNotification(config, "Авто: проверка $attempts/${AutoConnectPolicy.MAX_ATTEMPTS}"))
                    handleConnectLocked(config.id, sessionId, autoMode = true, fragment = fragment)
                    currentCoroutineContext().ensureActive()
                    if (sessionId != sessionSequence.get()) return false
                    if (_vpnStats.value.status == VpnStatus.CONNECTED) return true
                    lastReport = _vpnStats.value.health
                }
            }
            return false
        }
        try {
            withTimeout(120_000L) {
                if (tryPool(repository.getAllConfigs().take(100), if (settings.autopilotAllowedOnlyFavorites) 12 else 6)) return@withTimeout
                if (sessionId != sessionSequence.get()) return@withTimeout
                if (!settings.autopilotAllowedOnlyFavorites && attempts < AutoConnectPolicy.MAX_ATTEMPTS) {
                    _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true,
                        progressMessage = "Обновляем публичные конфигурации")
                    val fresh = PublicConfigFetcher.fetchCandidates { message ->
                        if (sessionId == sessionSequence.get()) _vpnStats.value = _vpnStats.value.copy(progressMessage = message)
                    }
                    repository.addConfigs(fresh)
                    tryPool(fresh)
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Overall selection deadline, not an endless scan on a weak connection.
        }
        currentCoroutineContext().ensureActive()
        if (sessionId != sessionSequence.get() || _vpnStats.value.status == VpnStatus.CONNECTED) return
        cleanupResources()
        _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, autoMode = true, health = lastReport,
            errorMessage = "Рабочий маршрут не найден. Авто не будет показывать фиктивное подключение. Добавьте надёжную подписку или повторите поиск")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun handleConnectLocked(configId: String, sessionId: Long, autoMode: Boolean = false, fragment: Boolean = false) {
        if (sessionId != sessionSequence.get()) return

        // 0. DEFENSIVE: Verify VPN permission BEFORE any heavy work (prevents crash on Connect)
        val prepare = prepareVpnPermission()
        if (prepare != null) {
            cleanupResources()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                errorMessage = "Нет разрешения VPN. Предоставьте разрешение в системных настройках."
            )
            if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        // 1. Load validated config
        val config = if (configId.isNotBlank()) {
            repository.getAllConfigs().firstOrNull { it.id == configId }
        } else {
            repository.getActiveConfig() ?: repository.getAllConfigs().firstOrNull()
        }

        if (config == null || config.address.isBlank() || config.port <= 0 || config.uuid.isBlank()) {
            val err = if (config == null) "Конфигурация сервера не найдена" else "Некорректные параметры сервера (UUID/Адрес)"
            cleanupResources()
            _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, activeConfig = config, errorMessage = err)
            if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val base = repository.settingsFlow.value
        val settingsSnapshot: AppSettings = if (autoMode) AutoConnectPolicy.settings(base, fragment) else base

        _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, activeConfig = config,
            autoMode = autoMode, progressMessage = if (fragment) "Проверяем TLS-фрагментацию" else "Запускаем и проверяем маршрут")
        // Keep foreground alive (already started in onStartCommand)
        if (!safeStartForeground(1, createNotification(config, "Инициализация ядра связи…"))) {
            cleanupResources()
            return
        }

        try {
            // 2. Evaluate Network Profile with consistent settings snapshot
            val netProfile = NetworkProfileManager.evaluateNetwork(
                context = this@VlessVpnService,
                settings = settingsSnapshot,
                serverExplicitSni = config.sni,
                serverAddress = config.address
            )

            if (sessionId != sessionSequence.get()) return

            // 3. Teardown any lingering core instance cleanly before spawning new
            cleanupResources()

            initLibboxEnvironment()

            // 4. Launch via Sing-Box Engine (Full unified support for VLESS Reality, VMess, Trojan, ShadowTLS, uTLS)
            val localProbe = LocalProbeProxy.allocate()
            probeProxy = localProbe
            val singBoxJson = SingBoxManager.generateConfig(
                context = this@VlessVpnService,
                config = config,
                settings = settingsSnapshot,
                networkProfile = netProfile,
                probeProxy = localProbe
            )

            SingBoxManager.validateGeneratedConfig(singBoxJson).getOrThrow()
            // Structural JSON checks alone cannot detect native schema incompatibility.
            Libbox.checkConfig(singBoxJson)

            // 5. Setup LibboxPlatformInterface and CommandServer
            val adapter = LibboxPlatformInterface(this@VlessVpnService, settingsSnapshot.bypassApps) { pfd ->
                vpnInterface = pfd
            }
            platformAdapter = adapter

            val serverHandler = object : CommandServerHandler {
                // Android VPN mode has no system HTTP proxy, but libbox requires an object.
                override fun getSystemProxyStatus(): SystemProxyStatus =
                    NativeCallbackValues.disabledSystemProxy()
                override fun serviceReload() {}
                override fun serviceStop() {
                    // Core stopped unexpectedly
                    serviceScope.launch {
                        operationMutex.withLock {
                            if (sessionId == sessionSequence.get() && _vpnStats.value.status == VpnStatus.CONNECTED) {
                                cleanupResources()
                                _vpnStats.value = VpnSessionStats(
                                    status = VpnStatus.ERROR,
                                    activeConfig = config,
                                    errorMessage = "Ядро туннеля было остановлено системой"
                                )
                                if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
                            }
                        }
                    }
                }
                override fun setSystemProxyEnabled(p0: Boolean) {}
                override fun writeDebugMessage(msg: String?) {
                    Log.d("SingBoxCore", VlessApplication.sanitizeLog(msg))
                }
            }

            // 5b. Start CommandServer and load config - protected boundary for libbox/JNI
            val server = try {
                val s = CommandServer(serverHandler, adapter)
                // Retain ownership before any operation which may fail.
                commandServer = s
                s.start()
                s.startOrReloadService(singBoxJson, OverrideOptions())
                s
            } catch (t: Throwable) {
                Log.e("VlessVpnService", "CommandServer startOrReloadService failed (libbox/JNI)", t)
                CrashReportManager.recordException(applicationContext, Thread.currentThread(), t, "VPN_START")
                cleanupResources()
                _vpnStats.value = VpnSessionStats(
                    status = VpnStatus.ERROR,
                    activeConfig = config,
                    errorMessage = "Ошибка запуска ядра sing-box: ${sanitizeError(t.message)}"
                )
                if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
            commandServer = server

            // 6. Connect CommandClient for real traffic metrics
            startCommandClientListener()

            if (sessionId != sessionSequence.get()) {
                cleanupResources()
                return
            }

            // Do not publish CONNECTED just because a TUN interface exists.
            TunnelHealthChecker.activeProxy = localProbe
            _vpnStats.value = _vpnStats.value.copy(progressMessage = "Проверяем HTTPS через выбранный сервер")
            safeStartForeground(1, createNotification(config, "Проверка реального маршрута…"))
            val report = TunnelHealthChecker.check(localProbe, timeoutMs = 4000)
            currentCoroutineContext().ensureActive()
            if (sessionId != sessionSequence.get()) { cleanupResources(); return }
            if (!report.internet || autoMode && !report.preferredServices) {
                cleanupResources()
                repository.updateConfig(config.copy(healthState = "DEGRADED", httpLatencyMs = -1,
                    lastCheck = System.currentTimeMillis(), failureCount = config.failureCount + 1))
                _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, activeConfig = config,
                    autoMode = autoMode, health = report,
                    errorMessage = if (report.internet) "HTTPS работает, но YouTube/Telegram веб не прошли проверку"
                        else "Сервер не передаёт HTTPS. Пинг порта не подтверждает работу VPN")
                if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
            val startTime = System.currentTimeMillis()
            repository.updateConfig(config.copy(healthState = "HEALTHY", httpLatencyMs = report.latencyMs,
                lastCheck = startTime, failureCount = 0))
            repository.setActive(config.id)
            NetworkProfileManager.markProfileWorking(netProfile)
            _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTED, activeConfig = config,
                connectedSinceTimestamp = startTime, autoMode = autoMode, health = report,
                profileLabel = if (fragment) "TLS-фрагментация" else if (autoMode) "Совместимый TLS" else "Параметры сервера")
            safeStartForeground(1, createNotification(config, "Маршрут проверен • ${report.latencyMs} мс"))
            startStatsUpdater(startTime)
            if (autoMode) startAutoHealthMonitor(sessionId)

        } catch (e: CancellationException) {
            cleanupResources()
            throw e
        } catch (se: SecurityException) {
            Log.e("VlessVpnService", "SecurityException during VPN setup", se)
            cleanupResources()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                activeConfig = config,
                errorMessage = "SecurityException: ${sanitizeError(se.message)}"
            )
            if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ise: IllegalStateException) {
            Log.e("VlessVpnService", "IllegalState during VPN setup (possible TUN or libbox issue)", ise)
            cleanupResources()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                activeConfig = config,
                errorMessage = "IllegalState: ${sanitizeError(ise.message)} (TUN creation or libbox failure)"
            )
            if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ioe: java.io.IOException) {
            Log.e("VlessVpnService", "IO error during VPN setup", ioe)
            cleanupResources()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                activeConfig = config,
                errorMessage = "IO error: ${sanitizeError(ioe.message)}"
            )
            if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            // Catches JVM/linkage failures, NOT SIGSEGV/abort in native code.
            CrashReportManager.recordException(applicationContext, Thread.currentThread(), t, "VPN_START")
            Log.e("VlessVpnService", "FATAL Throwable in VPN tunnel setup (JNI/libbox)", t)
            cleanupResources()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                activeConfig = config,
                errorMessage = "Native error: ${sanitizeError(t.message ?: t.javaClass.simpleName)}"
            )
            if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun sanitizeError(msg: String?): String =
        VlessApplication.sanitizeLog(msg).ifBlank { "Неизвестная ошибка" }.take(200)

    private suspend fun handleDisconnect(): Unit = operationMutex.withLock {
        _vpnStats.value = _vpnStats.value.copy(status = VpnStatus.STOPPING)
        cleanupResources()
        _vpnStats.value = VpnSessionStats(status = VpnStatus.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startCommandClientListener() {
        try {
            val clientHandler = object : CommandClientHandler {
                override fun clearLogs() {}
                override fun connected() {}
                override fun disconnected(p0: String?) {}
                override fun initializeClashMode(p0: StringIterator?, p1: String?) {}
                override fun setDefaultLogLevel(p0: Int) {}
                override fun updateClashMode(p0: String?) {}
                override fun writeConnectionEvents(p0: ConnectionEvents?) {}
                override fun writeGroups(p0: OutboundGroupIterator?) {}
                override fun writeLogs(p0: LogIterator?) {}
                override fun writeServiceStatus(p0: ServiceStatusMessage?) {}
                override fun writeStatus(status: StatusMessage?) {
                    if (status != null && _vpnStats.value.status == VpnStatus.CONNECTED) {
                        _vpnStats.value = _vpnStats.value.copy(
                            bytesIn = status.downlinkTotal,
                            bytesOut = status.uplinkTotal,
                            downloadSpeedBps = status.downlink,
                            uploadSpeedBps = status.uplink
                        )
                    }
                }
            }

            val clientOptions = CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                statusInterval = 1000000000L // 1 second in ns
            }

            val client = Libbox.newCommandClient(clientHandler, clientOptions)
            commandClient = client
            // CommandClient.connect() is a BLOCKING streaming loop that only returns after
            // disconnect(). It must run on its own thread — never on a coroutine holding
            // operationMutex, otherwise CONNECTED is never reached and disconnect deadlocks.
            Thread {
                try {
                    client.connect()
                } catch (e: Throwable) {
                    Log.w("VlessVpnService", "CommandClient listener stopped: ${sanitizeError(e.message)}")
                }
            }.apply {
                name = "libbox-command-client"
                isDaemon = true
            }.start()
        } catch (e: Exception) {
            Log.w("VlessVpnService", "CommandClient listener note: ${e.message}")
        }
    }

    private fun startAutoHealthMonitor(sessionId: Long) {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            var failures = 0
            while (isActive && sessionId == sessionSequence.get()) {
                delay(if (isScreenInteractive) 30_000L else 60_000L)
                val proxy = probeProxy ?: return@launch
                val report = TunnelHealthChecker.check(proxy)
                if (sessionId != sessionSequence.get() || probeProxy !== proxy) return@launch
                _vpnStats.value = _vpnStats.value.copy(health = report)
                failures = if (report.preferredServices) 0 else failures + 1
                if (failures >= 3) {
                    if (repository.settingsFlow.value.failoverEnabled && autoRecoveryAttempts < 2) {
                        autoRecoveryAttempts++
                        startAuto(this@VlessVpnService, recovery = true)
                    } else operationMutex.withLock {
                        if (sessionId == sessionSequence.get()) {
                            cleanupResources()
                            _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, autoMode = true,
                                health = report, errorMessage = "Маршрут перестал работать. Автоповторы ограничены; нажмите «Авто» для новой проверки")
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                    return@launch
                }
            }
        }
    }

    private fun startStatsUpdater(startTime: Long) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && _vpnStats.value.status == VpnStatus.CONNECTED) {
                // When screen is off, relax ticker interval to 5000ms to preserve battery and CPU cycles
                val delayTime = if (isScreenInteractive) 1000L else 5000L
                delay(delayTime)
                val duration = (System.currentTimeMillis() - startTime) / 1000
                _vpnStats.value = _vpnStats.value.copy(
                    durationSeconds = duration
                )
            }
        }
    }

    /**
     * Closes native objects, listeners and TUN file descriptors without resetting UI state.
     */
    private fun cleanupResources() {
        // Idempotent, safe cleanup. Never call stopSelf inside here.
        statsJob?.cancel()
        statsJob = null
        healthJob?.cancel()
        healthJob = null
        val localProbe = probeProxy.also { probeProxy = null }
        TunnelHealthChecker.clear(localProbe)

        val client = commandClient.also { commandClient = null }
        val server = commandServer.also { commandServer = null }
        val adapter = platformAdapter.also { platformAdapter = null }
        val tun = vpnInterface.also { vpnInterface = null }
        // Independent cleanup: closeService failure must not prevent server.close.
        try { client?.disconnect() } catch (_: Throwable) {}
        try { server?.closeService() } catch (_: Throwable) {}
        try { server?.close() } catch (_: Throwable) {}
        try { adapter?.closeDefaultInterfaceMonitor(null) } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Throwable) {}

        // Do NOT call stopSelf or change _vpnStats here — caller decides state
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vless_card_vpn_service",
                "VLESS Card VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Состояние туннеля связи"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun safeStartForeground(id: Int, notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(id, notification)
            }
            true
        } catch (t: Throwable) {
            Log.e("VlessVpnService", "safeStartForeground failed (${t.javaClass.simpleName}): ${t.message}", t)
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                errorMessage = "Ошибка запуска службы: ${sanitizeError(t.message ?: t.javaClass.simpleName)}"
            )
            false
        }
    }

    private fun prepareVpnPermission(): Intent? = VpnService.prepare(this)

    private fun createNotification(config: VlessConfig?, statusText: String): Notification {
        // Minimal safe notification to satisfy FGS requirement even if config is null during early start
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, VlessVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "vless_card_vpn_service")
            .setContentTitle("VLESS Stealth Core: ${config?.name ?: "Активное подключение"}")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", disconnectPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onRevoke() {
        sessionSequence.incrementAndGet()
        connectionJob?.cancel()
        serviceScope.launch {
            handleDisconnect()
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        sessionSequence.incrementAndGet()
        connectionJob?.cancel()
        screenStateReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            screenStateReceiver = null
        }
        // Never race closeService against a synchronous native start on Dispatchers.IO.
        serviceScope.launch(NonCancellable) {
            operationMutex.withLock { cleanupResources() }
            repository.close()
            serviceScope.cancel()
        }
        super.onDestroy()
    }
}
