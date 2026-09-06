package com.vlesscardvpn.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vlesscardvpn.MainActivity
import com.vlesscardvpn.core.LibboxPlatformInterface
import com.vlesscardvpn.core.NetworkProfileManager
import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
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
    val errorMessage: String? = null
)

class VlessVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.vlesscardvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.vlesscardvpn.DISCONNECT"
        const val EXTRA_CONFIG_ID = "config_id"

        private val _vpnStats = MutableStateFlow(VpnSessionStats())
        val vpnStats = _vpnStats.asStateFlow()

        fun startVpn(context: Context, config: VlessConfig) {
            val intent = Intent(context, VlessVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONFIG_ID, config.id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopVpn(context: Context) {
            val intent = Intent(context, VlessVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val operationMutex = Mutex()
    private val sessionSequence = AtomicLong(0L)

    private var connectionJob: Job? = null
    private var statsJob: Job? = null
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var platformAdapter: LibboxPlatformInterface? = null

    private lateinit var repository: AppRepository

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(applicationContext)
        createNotificationChannel()
        initLibboxEnvironment()
    }

    private fun initLibboxEnvironment() {
        try {
            val baseDir = File(filesDir, "singbox").apply { mkdirs() }
            val workingDir = File(cacheDir, "singbox_work").apply { mkdirs() }
            val tempDir = File(cacheDir, "singbox_temp").apply { mkdirs() }

            val options = SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workingDir.absolutePath
                tempPath = tempDir.absolutePath
                debug = false
            }
            Libbox.setup(options)
        } catch (t: Throwable) {
            // JNI / native setup can throw on bad AAR or ABI mismatch - do not crash the service
            Log.e("VlessVpnService", "Libbox.setup failed (possible AAR/ABI issue)", t)
        }
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
            ACTION_CONNECT -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID) ?: ""
                val sessionId = sessionSequence.incrementAndGet()

                // CRITICAL FIX: MUST call startForeground synchronously (Android 12+ / FGS rules)
                // before ANY coroutine launch or heavy work. This was the primary cause of app exit/crash.
                val initialNotification = try {
                    createNotification(null, "Подключение...")
                } catch (t: Throwable) {
                    null
                }
                if (initialNotification != null) {
                    try {
                        startForeground(1, initialNotification)
                    } catch (se: SecurityException) {
                        _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, errorMessage = "Нет разрешения на foreground service (VPN permission)")
                        return START_NOT_STICKY
                    } catch (t: Throwable) {
                        _vpnStats.value = VpnSessionStats(status = VpnStatus.ERROR, errorMessage = "Не удалось запустить foreground: ${t.javaClass.simpleName}")
                        return START_NOT_STICKY
                    }
                } else {
                    // Fallback: still try to start foreground with minimal notification
                    try {
                        startForeground(1, createNotification(null, "Подключение"))
                    } catch (_: Exception) {
                        // If even this fails, we cannot continue as FGS
                        return START_NOT_STICKY
                    }
                }

                connectionJob?.cancel()
                connectionJob = serviceScope.launch {
                    handleConnect(configId, sessionId)
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
        return START_NOT_STICKY
    }

    private suspend fun handleConnect(configId: String, sessionId: Long) = operationMutex.withLock {
        if (sessionId != sessionSequence.get()) return

        // 0. DEFENSIVE: Verify VPN permission BEFORE any heavy work (prevents crash on Connect)
        val prepare = prepareVpnPermission()
        if (prepare != null) {
            cleanupResources()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                errorMessage = "Нет разрешения VPN. Предоставьте разрешение в системных настройках."
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }

        val settingsSnapshot: AppSettings = repository.settingsFlow.value

        _vpnStats.value = VpnSessionStats(status = VpnStatus.CONNECTING, activeConfig = config)
        // Keep foreground alive (already started in onStartCommand)
        try { startForeground(1, createNotification(config, "Инициализация ядра связи...")) } catch (_: Exception) {}

        try {
            // 2. Evaluate Network Profile with consistent settings snapshot
            val netProfile = NetworkProfileManager.evaluateNetwork(
                context = this@VlessVpnService,
                settings = settingsSnapshot,
                serverExplicitSni = config.sni,
                serverAddress = config.address
            )

            if (sessionId != sessionSequence.get()) return

            // 3. Generate Sing-Box Core Config JSON and validate before applying
            val singBoxJson = SingBoxManager.generateConfig(
                context = this@VlessVpnService,
                config = config,
                settings = settingsSnapshot,
                networkProfile = netProfile
            )

            SingBoxManager.validateGeneratedConfig(singBoxJson).getOrThrow()

            // 4. Teardown any lingering core instance cleanly before spawning new
            cleanupResources()

            // 5. Setup LibboxPlatformInterface and CommandServer
            val adapter = LibboxPlatformInterface(this@VlessVpnService) { pfd ->
                vpnInterface = pfd
            }
            platformAdapter = adapter

            val serverHandler = object : CommandServerHandler {
                override fun getSystemProxyStatus(): SystemProxyStatus? = null
                override fun serviceReload() {}
                override fun serviceStop() {
                    // Core stopped unexpectedly
                    serviceScope.launch {
                        operationMutex.withLock {
                            if (_vpnStats.value.status == VpnStatus.CONNECTED) {
                                cleanupResources()
                                _vpnStats.value = VpnSessionStats(
                                    status = VpnStatus.ERROR,
                                    activeConfig = config,
                                    errorMessage = "Ядро туннеля было остановлено системой"
                                )
                                stopForeground(STOP_FOREGROUND_REMOVE)
                            }
                        }
                    }
                }
                override fun setSystemProxyEnabled(p0: Boolean) {}
                override fun writeDebugMessage(msg: String?) {
                    Log.d("SingBoxCore", msg ?: "")
                }
            }

            // 5b. Start CommandServer and load config - protected boundary for libbox/JNI
            val server = try {
                val s = CommandServer(serverHandler, adapter)
                s.start()
                s.startOrReloadService(singBoxJson, OverrideOptions())
                s
            } catch (t: Throwable) {
                Log.e("VlessVpnService", "CommandServer startOrReloadService failed (libbox/JNI)", t)
                cleanupResources()
                _vpnStats.value = VpnSessionStats(
                    status = VpnStatus.ERROR,
                    activeConfig = config,
                    errorMessage = "Ошибка запуска ядра sing-box: ${sanitizeError(t.message)}"
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
            commandServer = server

            // 6. Connect CommandClient for real traffic metrics
            startCommandClientListener()

            if (sessionId != sessionSequence.get()) {
                cleanupResources()
                return
            }

            // 7. CRITICAL: End-to-End Verification Before setting CONNECTED status
            // Never promote to CONNECTED on partial success (TUN open but no proxy traffic)
            startForeground(1, createNotification(config, "Проверка сквозного защищенного соединения..."))

            var verified = false
            var retryCount = 0
            var lastVerifyLatency = -1

            while (retryCount < 3 && !verified && currentCoroutineContext().isActive && sessionId == sessionSequence.get()) {
                delay(900)
                val (isOk, latency) = PingTester.verifyEndToEndConnection(timeoutMs = 3500)
                if (isOk) {
                    verified = true
                    lastVerifyLatency = latency
                    NetworkProfileManager.markProfileWorking(netProfile)
                }
                retryCount++
            }

            if (sessionId != sessionSequence.get()) {
                cleanupResources()
                return
            }

            if (!verified) {
                // Verification failed through the proxy tunnel - NEVER mark CONNECTED!
                val diagnosticReason = "Сквозной тест HTTPS не пройден: узел не маршрутизирует трафик (ошибка авторизации или сбой Reality)"
                cleanupResources()
                _vpnStats.value = VpnSessionStats(
                    status = VpnStatus.ERROR,
                    activeConfig = config,
                    errorMessage = diagnosticReason
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }

            // 8. Connection successfully established and authenticated
            val startTime = System.currentTimeMillis()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.CONNECTED,
                activeConfig = config,
                connectedSinceTimestamp = startTime
            )

            startForeground(1, createNotification(config, "Подключено • Защищено (${lastVerifyLatency} мс)"))
            startStatsUpdater(startTime)

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
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ise: IllegalStateException) {
            Log.e("VlessVpnService", "IllegalState during VPN setup (possible TUN or libbox issue)", ise)
            cleanupResources()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                activeConfig = config,
                errorMessage = "IllegalState: ${sanitizeError(ise.message)} (TUN creation or libbox failure)"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ioe: java.io.IOException) {
            Log.e("VlessVpnService", "IO error during VPN setup", ioe)
            cleanupResources()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                activeConfig = config,
                errorMessage = "IO error: ${sanitizeError(ioe.message)}"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            // Last resort catch for JNI / native libbox crashes
            Log.e("VlessVpnService", "FATAL Throwable in VPN tunnel setup (JNI/libbox)", t)
            cleanupResources()
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.ERROR,
                activeConfig = config,
                errorMessage = "Native error: ${sanitizeError(t.message ?: t.javaClass.simpleName)}"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun sanitizeError(msg: String?): String {
        if (msg.isNullOrBlank()) return "unknown"
        return msg.replace(Regex("[0-9a-fA-F-]{8,}"), "[REDACTED]").take(200)
    }

    private suspend fun handleDisconnect() = operationMutex.withLock {
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
            client.connect()
        } catch (e: Exception) {
            Log.w("VlessVpnService", "CommandClient listener note: ${e.message}")
        }
    }

    private fun startStatsUpdater(startTime: Long) {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive && _vpnStats.value.status == VpnStatus.CONNECTED) {
                delay(1000)
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

        try { commandClient?.disconnect() } catch (_: Exception) {}
        commandClient = null

        try {
            commandServer?.closeService()
            commandServer?.close()
        } catch (_: Exception) {}
        commandServer = null

        try { platformAdapter?.closeDefaultInterfaceMonitor(null) } catch (_: Exception) {}
        platformAdapter = null

        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

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
            .setContentTitle("VLESS Stealth Core: ${config.name}")
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
        cleanupResources()
        serviceScope.cancel()
        super.onDestroy()
    }
}
