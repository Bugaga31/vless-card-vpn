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
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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
        const val EXTRA_CONFIG_NAME = "config_name"
        const val EXTRA_CONFIG_ADDRESS = "config_address"
        const val EXTRA_CONFIG_PORT = "config_port"
        const val EXTRA_CONFIG_UUID = "config_uuid"
        const val EXTRA_CONFIG_PROTO = "config_proto"
        const val EXTRA_CONFIG_SNI = "config_sni"
        const val EXTRA_CONFIG_PUBKEY = "config_pubkey"
        const val EXTRA_CONFIG_SHORTID = "config_shortid"

        private val _vpnStats = MutableStateFlow(VpnSessionStats())
        val vpnStats = _vpnStats.asStateFlow()

        fun startVpn(context: Context, config: VlessConfig) {
            val intent = Intent(context, VlessVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONFIG_ID, config.id)
                putExtra(EXTRA_CONFIG_NAME, config.name)
                putExtra(EXTRA_CONFIG_ADDRESS, config.address)
                putExtra(EXTRA_CONFIG_PORT, config.port)
                putExtra(EXTRA_CONFIG_UUID, config.uuid)
                putExtra(EXTRA_CONFIG_PROTO, config.protocolType)
                putExtra(EXTRA_CONFIG_SNI, config.sni)
                putExtra(EXTRA_CONFIG_PUBKEY, config.publicKey)
                putExtra(EXTRA_CONFIG_SHORTID, config.shortId)
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
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var statsJob: Job? = null
    private var currentConfig: VlessConfig? = null

    override fun onCreate() {
        super.onCreate()
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
        } catch (e: Exception) {
            Log.e("VlessVpnService", "Libbox setup warning / already setup", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val cfg = VlessConfig(
                    id = intent.getStringExtra(EXTRA_CONFIG_ID) ?: "active",
                    name = intent.getStringExtra(EXTRA_CONFIG_NAME) ?: "VPN Server",
                    address = intent.getStringExtra(EXTRA_CONFIG_ADDRESS) ?: "1.1.1.1",
                    port = intent.getIntExtra(EXTRA_CONFIG_PORT, 443),
                    uuid = intent.getStringExtra(EXTRA_CONFIG_UUID) ?: "",
                    protocolType = intent.getStringExtra(EXTRA_CONFIG_PROTO) ?: "vless",
                    sni = intent.getStringExtra(EXTRA_CONFIG_SNI) ?: "yandex.ru",
                    publicKey = intent.getStringExtra(EXTRA_CONFIG_PUBKEY) ?: "",
                    shortId = intent.getStringExtra(EXTRA_CONFIG_SHORTID) ?: "",
                    isActive = true
                )
                currentConfig = cfg
                startVpnTunnel(cfg)
            }
            ACTION_DISCONNECT -> {
                stopVpnTunnel()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startVpnTunnel(config: VlessConfig) {
        serviceScope.launch {
            _vpnStats.value = VpnSessionStats(
                status = VpnStatus.CONNECTING,
                activeConfig = config
            )
            startForeground(1, createNotification(config, "Initializing sing-box core..."))

            try {
                // 1. Evaluate Network & MTU Profile
                val netProfile = NetworkProfileManager.evaluateNetwork(
                    context = this@VlessVpnService,
                    settings = AppSettings(),
                    serverExplicitSni = config.sni,
                    serverAddress = config.address
                )

                // 2. Generate Sing-Box Core Config JSON
                val singBoxJson = SingBoxManager.generateConfig(
                    context = this@VlessVpnService,
                    config = config,
                    settings = AppSettings(),
                    networkProfile = netProfile
                )

                // 3. Setup CommandServer with LibboxPlatformInterface
                val platformInterface = LibboxPlatformInterface(this@VlessVpnService) { pfd ->
                    vpnInterface = pfd
                }

                val serverHandler = object : CommandServerHandler {
                    override fun getSystemProxyStatus(): SystemProxyStatus? = null
                    override fun serviceReload() {}
                    override fun serviceStop() {
                        stopVpnTunnel()
                    }
                    override fun setSystemProxyEnabled(p0: Boolean) {}
                    override fun writeDebugMessage(msg: String?) {
                        Log.d("SingBoxCore", msg ?: "")
                    }
                }

                stopCommandServer()

                val server = CommandServer(serverHandler, platformInterface)
                commandServer = server
                server.start()
                server.startOrReloadService(singBoxJson, OverrideOptions())

                // 4. Start Client to listen to actual traffic throughput from Sing-Box
                startCommandClientListener()

                // 5. CRITICAL: End-to-End Verification Before setting CONNECTED status
                startForeground(1, createNotification(config, "Verifying end-to-end routing..."))
                var verified = false
                var retryCount = 0
                while (retryCount < 3 && !verified) {
                    delay(1000)
                    val (isOk, latency) = PingTester.verifyEndToEndConnection(timeoutMs = 3500)
                    if (isOk) {
                        verified = true
                        NetworkProfileManager.markProfileWorking(netProfile)
                        Log.i("VlessVpnService", "End-to-end verified successfully ($latency ms)")
                    }
                    retryCount++
                }

                if (!verified) {
                    // Fallback verify check with detailed latency
                    val breakdown = PingTester.testDetailedLatency(config, 3000)
                    if (!breakdown.success) {
                        _vpnStats.value = _vpnStats.value.copy(
                            status = VpnStatus.ERROR,
                            errorMessage = breakdown.errorReason ?: "End-to-end routing failed (Target unreachable)"
                        )
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopVpnTunnel()
                        return@launch
                    }
                }

                val startTime = System.currentTimeMillis()
                _vpnStats.value = VpnSessionStats(
                    status = VpnStatus.CONNECTED,
                    activeConfig = config,
                    connectedSinceTimestamp = startTime
                )

                startForeground(1, createNotification(config, "Connected • Protected (${netProfile.recommendedSni})"))
                startStatsUpdater(startTime)

            } catch (e: Exception) {
                Log.e("VlessVpnService", "Fatal error starting VPN core", e)
                _vpnStats.value = _vpnStats.value.copy(
                    status = VpnStatus.ERROR,
                    errorMessage = e.localizedMessage ?: "Core engine failed to initialize"
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopVpnTunnel()
            }
        }
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
                    if (status != null) {
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
                command = Libbox.CommandStatus
                statusInterval = 1000000000L // 1 second in ns
            }

            commandClient = Libbox.newCommandClient(clientHandler, clientOptions)
            commandClient?.connect()
        } catch (e: Exception) {
            Log.e("VlessVpnService", "CommandClient listener start failed", e)
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

    private fun stopCommandServer() {
        try {
            commandClient?.disconnect()
            commandClient = null
        } catch (_: Exception) {}

        try {
            commandServer?.closeService()
            commandServer?.close()
            commandServer = null
        } catch (_: Exception) {}
    }

    private fun stopVpnTunnel() {
        statsJob?.cancel()
        stopCommandServer()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null

        _vpnStats.value = VpnSessionStats(status = VpnStatus.DISCONNECTED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vless_card_vpn_service",
                "VLESS Card VPN Core",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active VPN Tunnel Status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(config: VlessConfig, statusText: String): Notification {
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
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
