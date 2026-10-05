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
import com.vlesscardvpn.core.ByeDpiRunner
import com.vlesscardvpn.data.AdaptiveRouteMemory
import com.vlesscardvpn.domain.RouteProfile
import com.vlesscardvpn.domain.AdaptiveRoutePolicy
import com.vlesscardvpn.domain.NetworkDiagnosticLog
import com.vlesscardvpn.domain.NetworkDiagnosticEvent
import com.vlesscardvpn.domain.DiagnosticPhase
import com.vlesscardvpn.domain.DiagnosticFailure
import com.vlesscardvpn.core.CrashReportManager
import com.vlesscardvpn.VlessApplication
import go.Seq

import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.domain.AutoConnectPolicy
import com.vlesscardvpn.domain.AutoTcpPreflight
import com.vlesscardvpn.domain.AutoSearchPolicy
import com.vlesscardvpn.domain.AutoRouteAttempt
import com.vlesscardvpn.domain.ByeDpiPreset
import com.vlesscardvpn.domain.ByeDpiArgs
import com.vlesscardvpn.domain.AutoPingPolicy
import com.vlesscardvpn.domain.ConnectCheckMode
import com.vlesscardvpn.domain.LocalProbeProxy
import com.vlesscardvpn.domain.TunnelHealthChecker
import com.vlesscardvpn.domain.TunnelHealthReport
import com.vlesscardvpn.domain.DirectStrategies
import com.vlesscardvpn.domain.DirectStrategy
import com.vlesscardvpn.domain.DpiEngine
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
    val profileLabel: String = "Параметры сервера",
    /** "Без сервера" (как ByeByeDPI): local ByeDPI/tpws only, no VPN server. */
    val direct: Boolean = false
)

class VlessVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.vlesscardvpn.CONNECT"
        const val ACTION_AUTO = "com.vlesscardvpn.AUTO"
        const val ACTION_DISCONNECT = "com.vlesscardvpn.DISCONNECT"
        const val ACTION_DIRECT = "com.vlesscardvpn.DIRECT"
        const val EXTRA_CONFIG_ID = "config_id"

        private val _vpnStats = MutableStateFlow(VpnSessionStats())
        val vpnStats = _vpnStats.asStateFlow()

        fun startVpn(context: Context, config: VlessConfig) {
            // Give immediate feedback and prevent duplicate connect taps while Android starts the service.
            _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, activeConfig = config,
                progressMessage = "Запускаем выбранный сервер")
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
            _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true,
                progressMessage = "Начинаем проверку серверов")
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

        /** "Без сервера": local ByeDPI/zapret desync for all apps, like ByeByeDPI's VPN mode. */
        fun startDirect(context: Context) {
            _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true, direct = true,
                progressMessage = "Без сервера: подбираем стратегию обхода")
            val intent = Intent(context, VlessVpnService::class.java).apply { action = ACTION_DIRECT }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (_: Exception) {
                _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, direct = true,
                    errorMessage = "Не удалось запустить режим без сервера")
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
    private var byeDpi: ByeDpiRunner? = null
    private val routeMemory by lazy { AdaptiveRouteMemory(this) }
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
            ACTION_CONNECT, ACTION_AUTO, ACTION_DIRECT -> {
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
                    if (intent.action == ACTION_DIRECT) {
                        operationMutex.withLock { handleDirectLocked(sessionId, fromAuto = false) }
                        if (_vpnStats.value.status == VpnStatus.ERROR) stopSelf()
                    } else if (intent.action == ACTION_AUTO) {
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
        operationMutex.withLock {
            val settings = repository.settingsFlow.value
            val custom = customByeDpi(settings)
            if (custom?.isFailure == true) {
                _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR,
                    errorMessage = "Своя стратегия ByeDPI: ${custom.exceptionOrNull()?.message}. Исправьте или очистите поле в настройках")
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@withLock
            }
            handleConnectLocked(configId, sessionId,
                verifyHttps = ConnectCheckMode.normalize(settings.connectCheckMode) == ConnectCheckMode.HTTPS,
                customByeDpi = custom?.getOrNull())
        }
        // A terminal failure must not leave a lingering (START_STICKY) background service.
        if (_vpnStats.value.status == VpnStatus.ERROR) {
            stopSelf()
        }
    }

    /** Explicit foreground Auto action: bounded, cancellable selection using real authenticated tunnels. */
    private suspend fun handleAutoConnectLocked(sessionId: Long) {
        NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.AUTO_START))
        if (sessionId != sessionSequence.get()) return
        cleanupResources()
        _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true,
            progressMessage = "Авто: проверяем сохранённые серверы")
        val settings = repository.settingsFlow.value
        val custom = customByeDpi(settings)
        if (custom?.isFailure == true) {
            _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, autoMode = true,
                errorMessage = "Своя стратегия ByeDPI: ${custom.exceptionOrNull()?.message}. Исправьте или очистите поле в настройках")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        val pingOnly = ConnectCheckMode.normalize(settings.connectCheckMode) == ConnectCheckMode.PING
        // "Только пинг" or the user's own strategy: no preset sweep, just servers by best ping.
        if (pingOnly || custom != null) {
            // Auto never accepts an unverified route: "Только пинг" only shortens the list (best ping, one route each).
            handleAutoQuickLocked(sessionId, settings, custom?.getOrNull(), verifyHttps = true)
            return
        }
        val tried = mutableSetOf<String>()
        var attempts = 0
        var lastReport = TunnelHealthReport()
        var bestPartial: Pair<AutoRouteAttempt, TunnelHealthReport>? = null
        suspend fun tryPool(pool: List<VlessConfig>, limit: Int = AutoConnectPolicy.MAX_ATTEMPTS): Boolean {
            val supported = AutoConnectPolicy.rank(pool, settings.autopilotAllowedOnlyFavorites)
            val remembered = supported.mapNotNull { config ->
                routeMemory.get(config)?.let { AutoConnectPolicy.identity(config) to it }
            }.toMap()
            val candidates = AutoSearchPolicy.candidates(supported, remembered, settings.autopilotAllowedOnlyFavorites)
            _vpnStats.value = _vpnStats.value.copy(progressMessage = "Авто: быстрая проверка портов; затем проверим VPN")
            val measured = AutoTcpPreflight.measure(candidates)
            for (attempt in AutoSearchPolicy.plan(measured, remembered, tried, attemptLimit = limit - attempts)) {
                currentCoroutineContext().ensureActive()
                if (sessionId != sessionSequence.get() || attempts >= limit) return false
                if (!tried.add(attempt.key)) continue
                val config = attempt.config
                repository.recordAutoTcpHint(config.id, attempt.tcpMs)
                attempts++
                _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true,
                    activeConfig = config, progressMessage = "Авто: маршрут $attempts/${AutoConnectPolicy.MAX_ATTEMPTS} · ${attempt.label}")
                safeStartForeground(1, createNotification(config, "Авто: проверка $attempts/${AutoConnectPolicy.MAX_ATTEMPTS}"))
                handleConnectLocked(config.id, sessionId, autoMode = true, profile = attempt.profile, byeDpiPreset = attempt.byeDpiPreset ?: ByeDpiPreset.COMBINED)
                currentCoroutineContext().ensureActive()
                if (sessionId != sessionSequence.get()) return false
                if (_vpnStats.value.status == VpnStatus.CONNECTED) return true
                lastReport = _vpnStats.value.health
                if (AutoSearchPolicy.betterPartial(lastReport, bestPartial?.second)) {
                    bestPartial = attempt to lastReport
                }
            }
            return false
        }
        try {
            withTimeout(AutoSearchPolicy.DEADLINE_MS) {
                if (tryPool(repository.getAllConfigs(), if (settings.autopilotAllowedOnlyFavorites) AutoConnectPolicy.MAX_ATTEMPTS else AutoSearchPolicy.SAVED_ATTEMPTS)) return@withTimeout
                if (sessionId != sessionSequence.get()) return@withTimeout
                if (!settings.autopilotAllowedOnlyFavorites && attempts < AutoConnectPolicy.MAX_ATTEMPTS) {
                    _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true,
                        progressMessage = "Обновляем публичные конфигурации")
                    val sources = (repository.subscriptionSources().take(2) + PublicConfigFetcher.DEFAULT_PUBLIC_SOURCES.take(4)).distinct().take(4)
                    val fresh = PublicConfigFetcher.fetchCandidates(sources, maxSources = 4) { message ->
                        if (sessionId == sessionSequence.get()) _vpnStats.value = _vpnStats.value.copy(progressMessage = message)
                    }
                    repository.mergeCandidates(fresh)
                    tryPool(AutoSearchPolicy.importedRows(repository.getAllConfigs(), fresh))
                }
            }
        } catch (_: TimeoutCancellationException) {
            // Overall selection deadline, not an endless scan on a weak connection.
        }
        currentCoroutineContext().ensureActive()
        if (sessionId != sessionSequence.get() || _vpnStats.value.status == VpnStatus.CONNECTED) return
        // Preserve a proven HTTPS route when only the optional web checks failed.
        // Reconnect and re-check; the old report alone is never enough to claim CONNECTED.
        bestPartial?.let { (attempt, _) ->
            _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true,
                progressMessage = "Возвращаем HTTPS-маршрут; доступность сервисов ограничена")
            try {
                withTimeout(AutoSearchPolicy.FALLBACK_MS) {
                    handleConnectLocked(attempt.config.id, sessionId, autoMode = true,
                        profile = attempt.profile, requirePreferredServices = false, byeDpiPreset = attempt.byeDpiPreset ?: ByeDpiPreset.COMBINED)
                }
            } catch (_: TimeoutCancellationException) { }
            currentCoroutineContext().ensureActive()
            if (sessionId != sessionSequence.get() || _vpnStats.value.status == VpnStatus.CONNECTED) return
        }
        if (settings.directFallback && sessionId == sessionSequence.get()) {
            _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true, direct = true,
                progressMessage = "Серверы не прошли проверку ($attempts). Пробуем без сервера, как ByeByeDPI")
            handleDirectLocked(sessionId, fromAuto = true)
            if (sessionId != sessionSequence.get() || _vpnStats.value.status == VpnStatus.CONNECTED) return
        }
        cleanupResources()
        val skipped = AutoConnectPolicy.skippedSummary(repository.getAllConfigs())
        _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, autoMode = true, health = lastReport,
            errorMessage = "Проверено маршрутов: $attempts. HTTPS-маршрут не подтверждён. Пинг порта не означает рабочий VPN. Добавьте надёжную подписку или повторите поиск" +
                (skipped?.let { ". $it" } ?: ""))
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun handleConnectLocked(configId: String, sessionId: Long, autoMode: Boolean = false, profile: RouteProfile = RouteProfile.COMPATIBLE, requirePreferredServices: Boolean = autoMode, byeDpiPreset: ByeDpiPreset = ByeDpiPreset.COMBINED,
                                     verifyHttps: Boolean = true, customByeDpi: List<String>? = null) {
        if (sessionId != sessionSequence.get()) return

        // 0. DEFENSIVE: Verify VPN permission BEFORE any heavy work (prevents crash on Connect)
        NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.PERMISSION_CHECK))
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
        NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.CONFIG_LOAD))
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
        NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.ROUTE_START, profile = profile,
            preset = if (profile == RouteProfile.BYEDPI) byeDpiPreset else null))
        // The user's own strategy applies only to TLS-family servers which ByeDPI can carry.
        val custom = customByeDpi?.takeIf { RouteProfile.BYEDPI in AdaptiveRoutePolicy.profiles(config) }
        val routeLabel = when {
            custom != null -> AutoRouteAttempt.CUSTOM_LABEL
            profile == RouteProfile.BYEDPI -> byeDpiPreset.label
            else -> profile.label
        }
        val settingsSnapshot: AppSettings = if (autoMode) AdaptiveRoutePolicy.safeSettings(base, profile) else base

        _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, activeConfig = config,
            autoMode = autoMode, progressMessage = if (autoMode) "Проверяем $routeLabel" else "Запускаем и проверяем маршрут")
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

            NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.CORE_INIT))
            initLibboxEnvironment()

            // 4. Launch via Sing-Box Engine (Full unified support for VLESS Reality, VMess, Trojan, ShadowTLS, uTLS)
            val localProbe = LocalProbeProxy.allocate()
            probeProxy = localProbe
            val antiDpiPort = when {
                custom != null -> ByeDpiRunner(this@VlessVpnService).also { byeDpi = it }.startCustom(custom)
                autoMode && profile == RouteProfile.BYEDPI ->
                    ByeDpiRunner(this@VlessVpnService).also { byeDpi = it }.start(byeDpiPreset, base.byeDpiMaskDomain)
                else -> null
            }
            val singBoxJson = SingBoxManager.generateConfig(
                context = this@VlessVpnService,
                config = config,
                settings = settingsSnapshot,
                networkProfile = netProfile,
                probeProxy = localProbe, antiDpiPort = antiDpiPort
            )

            NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.CONFIG_VALIDATE))
            SingBoxManager.validateGeneratedConfig(singBoxJson).getOrThrow()
            // Structural JSON checks alone cannot detect native schema incompatibility.
            Libbox.checkConfig(singBoxJson)

            // 5. Setup LibboxPlatformInterface and CommandServer
            val adapter = LibboxPlatformInterface(this@VlessVpnService, settingsSnapshot.bypassApps) { pfd ->
                vpnInterface = pfd
                NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.TUN_READY,
                    profile = if (autoMode) profile else null, preset = if (autoMode && profile == RouteProfile.BYEDPI) byeDpiPreset else null))
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
            NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.CORE_START))
            val server = try {
                val s = CommandServer(serverHandler, adapter)
                // Retain ownership before any operation which may fail.
                commandServer = s
                s.start()
                s.startOrReloadService(singBoxJson, OverrideOptions())
                s
            } catch (t: Throwable) {
                Log.e("VlessVpnService", "CommandServer startOrReloadService failed (libbox/JNI)", t)
                NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.CORE_FAILURE, failure = DiagnosticFailure.CORE))
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
            check(vpnInterface != null) { "Ядро не создало Android VPN-туннель" }
            TunnelHealthChecker.activate(localProbe, if (autoMode) profile else null,
                if (autoMode && profile == RouteProfile.BYEDPI) byeDpiPreset else null)
            if (!verifyHttps) {
                // "Только пинг": the tunnel is up; HTTPS is measured afterwards for display only.
                val startTime = System.currentTimeMillis()
                NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.PARTIAL,
                    profile = profile, preset = if (profile == RouteProfile.BYEDPI) byeDpiPreset else null))
                repository.setActive(config.id)
                _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTED, activeConfig = config,
                    connectedSinceTimestamp = startTime, autoMode = autoMode,
                    progressMessage = "Подключено по пингу; HTTPS проверяется в фоне",
                    profileLabel = if (autoMode || custom != null) routeLabel else "Параметры сервера")
                safeStartForeground(1, createNotification(config, "Подключено по пингу • ${routeLabel}"))
                startStatsUpdater(startTime)
                startDisplayHealthCheck(sessionId, localProbe, config, if (autoMode && custom == null) profile else null, byeDpiPreset)
                return
            }
            _vpnStats.value = _vpnStats.value.copy(progressMessage = "Проверяем HTTPS через выбранный сервер")
            safeStartForeground(1, createNotification(config, "Проверка реального маршрута…"))
            val report = TunnelHealthChecker.check(localProbe)
            currentCoroutineContext().ensureActive()
            if (sessionId != sessionSequence.get()) { cleanupResources(); return }
            if (!report.internet || requirePreferredServices && !report.usable) {
                cleanupResources()
                repository.recordTunnelHealth(config.id, healthy = false, latency = -1)
                _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, activeConfig = config,
                    autoMode = autoMode, health = report,
                    errorMessage = if (report.internet) "HTTPS работает, но YouTube/Telegram веб не прошли проверку"
                        else "Сервер не передаёт HTTPS. Пинг порта не подтверждает работу VPN")
                if (!autoMode) stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
            NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = if (report.preferredServices) DiagnosticPhase.CONNECTED else DiagnosticPhase.PARTIAL,
                profile = profile, preset = if (profile == RouteProfile.BYEDPI) byeDpiPreset else null))
            val startTime = System.currentTimeMillis()
            repository.recordTunnelHealth(config.id, healthy = true, latency = report.latencyMs)
            repository.setActive(config.id)
            if (autoMode && custom == null && report.usable) routeMemory.remember(config, profile, byeDpiPreset)
            NetworkProfileManager.markProfileWorking(netProfile)
            _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTED, activeConfig = config,
                connectedSinceTimestamp = startTime, autoMode = autoMode, health = report,
                profileLabel = if (autoMode || custom != null) routeLabel else "Параметры сервера")
            safeStartForeground(1, createNotification(config, if (report.preferredServices) "Маршрут проверен • ${report.latencyMs} мс" else "HTTPS работает; не все сервисы доступны"))
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
            NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.CORE_FAILURE, failure = DiagnosticFailure.CORE))
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

    private val directPrefs by lazy { getSharedPreferences("direct_mode", Context.MODE_PRIVATE) }

    /**
     * "Без сервера": one sing-box TUN pointed at a fixed loopback port; ByeDPI/tpws strategies are swapped behind
     * that port and each is accepted only when a real YouTube page downloads through it (throttling lets the tiny
     * generate_204 through). Bounded by [DirectStrategies.DEADLINE_MS].
     */
    private suspend fun handleDirectLocked(sessionId: Long, fromAuto: Boolean) {
        if (sessionId != sessionSequence.get()) return
        if (prepareVpnPermission() != null) {
            cleanupResources()
            _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, direct = true,
                errorMessage = "Нет разрешения VPN. Предоставьте разрешение в системных настройках.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        val settings = repository.settingsFlow.value
        val custom = customByeDpi(settings)?.getOrNull()
        cleanupResources()
        val plan = DirectStrategies.plan(custom, directPrefs.getString("last", null),
            ByeDpiRunner.available(this, DpiEngine.TPWS))
        val runner = ByeDpiRunner(this).also { byeDpi = it }
        var best: Pair<DirectStrategy, TunnelHealthReport>? = null
        var tried = 0
        var accepted: Pair<DirectStrategy, TunnelHealthReport>? = null
        var port = -1
        var running: DirectStrategy? = null
        try {
            val localProbe = LocalProbeProxy.allocate()
            probeProxy = localProbe
            port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            initLibboxEnvironment()
            val json = SingBoxManager.generateDirectConfig(this, settings, port, localProbe)
            SingBoxManager.validateGeneratedConfig(json).getOrThrow()
            Libbox.checkConfig(json)
            val adapter = LibboxPlatformInterface(this, settings.bypassApps) { pfd -> vpnInterface = pfd }
            platformAdapter = adapter
            val handler = object : CommandServerHandler {
                override fun getSystemProxyStatus(): SystemProxyStatus = NativeCallbackValues.disabledSystemProxy()
                override fun serviceReload() {}
                override fun serviceStop() {
                    serviceScope.launch { operationMutex.withLock {
                        if (sessionId == sessionSequence.get() && _vpnStats.value.status == VpnStatus.CONNECTED) {
                            cleanupResources()
                            _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, direct = true, errorMessage = "Ядро туннеля было остановлено системой")
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        }
                    } }
                }
                override fun setSystemProxyEnabled(p0: Boolean) {}
                override fun writeDebugMessage(msg: String?) { Log.d("SingBoxCore", VlessApplication.sanitizeLog(msg)) }
            }
            val server = CommandServer(handler, adapter)
            commandServer = server
            server.start()
            server.startOrReloadService(json, OverrideOptions())
            startCommandClientListener()
            check(vpnInterface != null) { "Ядро не создало Android VPN-туннель" }
            TunnelHealthChecker.activate(localProbe)
            withTimeout(DirectStrategies.DEADLINE_MS) {
                for (strategy in plan) {
                    currentCoroutineContext().ensureActive()
                    if (sessionId != sessionSequence.get()) return@withTimeout
                    tried++
                    _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true, direct = true,
                        progressMessage = "Без сервера $tried/${plan.size}: ${strategy.label}")
                    safeStartForeground(1, createNotification(null, "Без сервера: проверка $tried/${plan.size}"))
                    val started = runCatching { runner.startDirect(strategy, port, settings.byeDpiMaskDomain) }
                    val failure = started.exceptionOrNull()
                    if (failure is CancellationException) throw failure
                    if (failure != null) { running = null; continue }
                    running = strategy
                    var report = TunnelHealthChecker.directCheck(localProbe, DirectStrategies.CHECK_TIMEOUT_MS)
                    // Adaptive (--auto) strategies spend the first connection detecting the DPI; they have learned now.
                    if (!report.youtubeBulk && strategy.adaptive) report = TunnelHealthChecker.directCheck(localProbe, DirectStrategies.CHECK_TIMEOUT_MS)
                    if (report.youtubeBulk) { accepted = strategy to report; return@withTimeout }
                    if (report.directScore > (best?.second?.directScore ?: 0)) best = strategy to report
                }
            }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) { cleanupResources(); throw e }
        } catch (t: Throwable) {
            Log.e("VlessVpnService", "direct mode failed", t)
            cleanupResources()
            _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, autoMode = fromAuto, direct = true,
                errorMessage = "Режим без сервера не запустился: ${sanitizeError(t.message ?: t.javaClass.simpleName)}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (sessionId != sessionSequence.get()) { cleanupResources(); return }
        val chosen = accepted ?: best?.takeIf { it.second.youtube || it.second.internet }
        val (strategy, report) = chosen ?: run {
            cleanupResources()
            _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, autoMode = fromAuto, direct = true,
                errorMessage = "Без сервера: проверено стратегий $tried, ни одна не пропустила YouTube. Возможно, сайт заблокирован по IP — нужен рабочий сервер")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (running != strategy) {
            // The sweep left another strategy running: restart the best partial one behind the same port.
            if (runCatching { runner.startDirect(strategy, port, settings.byeDpiMaskDomain) }.isFailure) {
                cleanupResources()
                _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, autoMode = fromAuto, direct = true,
                    errorMessage = "Без сервера: не удалось перезапустить ${strategy.label}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
        }
        if (accepted != null) directPrefs.edit().putString("last", strategy.id).apply()
        val startTime = System.currentTimeMillis()
        _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTED, autoMode = fromAuto, direct = true, health = report,
            connectedSinceTimestamp = startTime, profileLabel = "Без сервера · ${strategy.label}",
            progressMessage = if (accepted != null) "YouTube загружается через локальный обход" else "Частично: YouTube не прошёл полную проверку")
        safeStartForeground(1, createNotification(null, "Без сервера • ${strategy.label}"))
        startStatsUpdater(startTime)
    }

    private fun customByeDpi(settings: AppSettings): Result<List<String>>? =
        settings.byeDpiCustomArgs.takeIf { it.isNotBlank() }?.let { ByeDpiArgs.parse(it, settings.byeDpiMaskDomain) }

    /** One HTTPS check after a ping-only connect: updates the status card, never tears the tunnel down. */
    private fun startDisplayHealthCheck(sessionId: Long, proxy: LocalProbeProxy, config: VlessConfig,
                                        rememberProfile: RouteProfile?, preset: ByeDpiPreset) {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            val report = runCatching { TunnelHealthChecker.check(proxy) }.getOrNull() ?: return@launch
            if (sessionId != sessionSequence.get() || probeProxy !== proxy || _vpnStats.value.status != VpnStatus.CONNECTED) return@launch
            _vpnStats.value = _vpnStats.value.copy(health = report, progressMessage = when {
                report.usable -> "Подключено по пингу; HTTPS работает"
                report.internet -> "Подключено по пингу; HTTPS есть, YouTube/Telegram не ответили"
                else -> "Подключено по пингу, но HTTPS не прошёл: сервер не работает. Нажмите «Авто» или «Без сервера»"
            })
            repository.recordTunnelHealth(config.id, healthy = report.internet, latency = report.latencyMs)
            if (rememberProfile != null && report.usable) routeMemory.remember(config, rememberProfile, preset)
            safeStartForeground(1, createNotification(config, when {
                report.usable -> "Подключено • HTTPS ${report.latencyMs} мс"
                report.internet -> "Подключено • не все сервисы доступны"
                else -> "Подключено по пингу • HTTPS не прошёл"
            }))
        }
    }

    /** Ping-ranked servers, one route each (own ByeDPI line / remembered / server parameters). */
    private suspend fun handleAutoQuickLocked(sessionId: Long, settings: AppSettings, custom: List<String>?, verifyHttps: Boolean) {
        var attempts = 0
        val tried = mutableSetOf<String>()
        suspend fun tryPool(pool: List<VlessConfig>): Boolean {
            val supported = AutoConnectPolicy.rank(pool, settings.autopilotAllowedOnlyFavorites)
            val remembered = supported.mapNotNull { c -> routeMemory.get(c)?.let { AutoConnectPolicy.identity(c) to it } }.toMap()
            _vpnStats.value = _vpnStats.value.copy(progressMessage = "Авто: пингуем серверы")
            val measured = AutoTcpPreflight.measure(AutoSearchPolicy.candidates(supported, remembered, settings.autopilotAllowedOnlyFavorites))
            measured.forEach { (c, ms) -> repository.recordAutoTcpHint(c.id, ms) }
            for (attempt in AutoPingPolicy.plan(measured, remembered, custom != null)) {
                currentCoroutineContext().ensureActive()
                if (sessionId != sessionSequence.get()) return false
                if (!tried.add(attempt.key)) continue
                attempts++
                _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true, activeConfig = attempt.config,
                    progressMessage = "Авто: лучший пинг ${attempt.tcpMs} мс · ${attempt.label}")
                handleConnectLocked(attempt.config.id, sessionId, autoMode = true, profile = attempt.profile,
                    requirePreferredServices = false, byeDpiPreset = attempt.byeDpiPreset ?: ByeDpiPreset.COMBINED,
                    verifyHttps = verifyHttps, customByeDpi = if (attempt.custom) custom else null)
                currentCoroutineContext().ensureActive()
                if (sessionId != sessionSequence.get()) return false
                if (_vpnStats.value.status == VpnStatus.CONNECTED) return true
            }
            return false
        }
        try {
            withTimeout(AutoSearchPolicy.DEADLINE_MS) {
                if (tryPool(repository.getAllConfigs())) return@withTimeout
                if (sessionId != sessionSequence.get() || settings.autopilotAllowedOnlyFavorites) return@withTimeout
                _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true, progressMessage = "Обновляем публичные конфигурации")
                val sources = (repository.subscriptionSources().take(2) + PublicConfigFetcher.DEFAULT_PUBLIC_SOURCES.take(4)).distinct().take(4)
                val fresh = PublicConfigFetcher.fetchCandidates(sources, maxSources = 4) { message ->
                    if (sessionId == sessionSequence.get()) _vpnStats.value = _vpnStats.value.copy(progressMessage = message)
                }
                repository.mergeCandidates(fresh)
                tryPool(AutoSearchPolicy.importedRows(repository.getAllConfigs(), fresh))
            }
        } catch (_: TimeoutCancellationException) { }
        currentCoroutineContext().ensureActive()
        if (sessionId != sessionSequence.get() || _vpnStats.value.status == VpnStatus.CONNECTED) return
        val last = _vpnStats.value
        if (settings.directFallback && sessionId == sessionSequence.get()) {
            _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, autoMode = true, direct = true,
                progressMessage = "Серверы не прошли проверку. Пробуем без сервера, как ByeByeDPI")
            handleDirectLocked(sessionId, fromAuto = true)
            if (sessionId != sessionSequence.get() || _vpnStats.value.status == VpnStatus.CONNECTED) return
        }
        cleanupResources()
        val skipped = AutoConnectPolicy.skippedSummary(repository.getAllConfigs())
        _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, autoMode = true, health = last.health,
            errorMessage = (if (attempts == 0) "Ни один сервер не ответил на пинг" else "Попыток: $attempts. " + (last.errorMessage ?: "Туннель не запустился")) +
                (skipped?.let { ". $it" } ?: ""))
        stopForeground(STOP_FOREGROUND_REMOVE)
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
                // A failed service website must not tear down otherwise proven HTTPS.
                failures = if (report.internet) 0 else failures + 1
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
        val runner = byeDpi.also { byeDpi = null }; runCatching { runner?.close() }

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
        NetworkDiagnosticLog.record(NetworkDiagnosticEvent(phase = DiagnosticPhase.STOPPED))
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
