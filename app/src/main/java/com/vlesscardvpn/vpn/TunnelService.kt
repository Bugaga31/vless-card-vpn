package com.vlesscardvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.vlesscardvpn.MainActivity
import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.ByeDpi
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.core.TProxyService
import com.vlesscardvpn.core.Tester
import com.vlesscardvpn.core.Tunnel
import com.vlesscardvpn.core.XrayCore
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.XrayConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TUN (VpnService) → hev-socks5-tunnel → SOCKS 127.0.0.1:10808 → Xray-core → selected servers (balancer)
 * or the built-in ByeDPI. The app itself is excluded from the VPN, so Xray/ByeDPI sockets go straight out.
 */
class TunnelService : VpnService() {
    companion object {
        const val ACTION_START = "com.vlesscardvpn.START"
        const val ACTION_STOP = "com.vlesscardvpn.STOP"
        private const val CHANNEL = "vpn"
        private const val TAG = "TunnelService"
        const val MTU = 1500
        const val IPV4 = "10.10.14.1"
        const val IPV6 = "fd66:6c65:7373::1"

        fun start(context: Context) {
            val i = Intent(context, TunnelService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) {
            context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var core: XrayCore.Instance? = null
    private var byeDpi: ByeDpi? = null
    private var checkJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { scope.launch { lock.withLock { teardown("") } ; stopSelf() } }
            else -> { // ACTION_START or always-on restart (null intent)
                foreground("Подключение…")
                scope.launch { lock.withLock { connect() } }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() { scope.launch { lock.withLock { teardown("VPN отключён системой или другим VPN") }; stopSelf() } }

    override fun onDestroy() {
        runCatching { kotlinx.coroutines.runBlocking { lock.withLock { teardown(null) } } }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun connect() {
        teardown(null)
        Store.init(this); XrayCore.init(this); Actions.init(this)
        Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Подключение…")
        try {
            val st = Store.state.value
            val settings = st.settings
            var selected = st.selected
            if (settings.mode != Mode.BYEDPI && selected.isEmpty()) {
                // Nothing chosen: take the best servers that passed the last test.
                selected = st.servers.filter { st.state(it).works }.sortedBy { st.state(it).realMs }.take(5)
            }
            if (settings.mode != Mode.BYEDPI && selected.isEmpty())
                error("Нет выбранных серверов. Откройте «Серверы», нажмите «Проверить» и отметьте рабочие.")
            selected = Actions.pinCertificates(selected)
            val withMasks = selected.map { it to Masks.byId(st.state(it).maskId) }
            val needByeDpi = settings.mode != Mode.SERVERS || withMasks.any { it.second?.viaByeDpi == true }
            var bdPort: Int? = null
            if (needByeDpi) {
                val b = ByeDpi(this); byeDpi = b
                bdPort = b.start(settings.byeDpiArgs, settings.byeDpiSni)
                Tunnel.byeDpiPort = bdPort
            }
            val config = XrayConfigBuilder.vpnConfig(withMasks, settings, bdPort)
            val c = XrayCore.Instance("vpn"); core = c
            c.start(config)
            check(c.running) { "Xray не запустился: ${c.lastStatus}" }

            val b = Builder().setSession("VLESS Card").setMtu(MTU)
                .addAddress(IPV4, 30).addRoute("0.0.0.0", 0)
                .addAddress(IPV6, 126).addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDisallowedApplication(packageName)
                .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
            val fd = b.establish() ?: error("Система не дала создать VPN (нет разрешения)")
            tun = fd
            TProxyService.start(filesDir, fd, settings.socksPort, MTU, IPV4, IPV6)

            val route = when (settings.mode) {
                Mode.BYEDPI -> "ByeDPI без сервера"
                Mode.HYBRID -> "${selected.size} серв. + ByeDPI для YouTube/Discord"
                Mode.SERVERS -> if (selected.size == 1) selected[0].name else "${selected.size} серверов · ${settings.balance.title.lowercase()}"
            }
            Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTED, "Подключено", route, "Проверяю интернет…", null, System.currentTimeMillis())
            foreground("Подключено · $route")
            watchNetwork()
            verifySoon(0)
        } catch (e: Throwable) {
            Log.w(TAG, "connect failed", e)
            Log.i("E2E", "connect failed: ${e.message}")
            teardown(e.message ?: e.javaClass.simpleName)
            stopSelf()
        }
    }

    /** End-to-end check through the running chain (app → 127.0.0.1:socks → Xray → server → internet). */
    private fun verifySoon(delayMs: Long) {
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(delayMs)
            val st = Store.state.value.settings
            val p = Tester.probeSocks(st.socksPort, st.testUrl)
            val cur = Tunnel.status.value
            if (cur.state != Tunnel.State.CONNECTED) return@launch
            val text = if (p.realMs > 0) "Интернет работает · ${p.realMs} мс · 256 КБ: ${Actions.yn(p.bigOk)} · YouTube: ${Actions.yn(p.ytOk)}"
                else "Нет ответа через выбранный маршрут (${p.error}). Проверьте серверы или включите маскировку."
            Tunnel.status.value = cur.copy(check = text, checkOk = p.works)
            Log.i("E2E", "check ok=${p.works} ms=${p.realMs} big=${p.bigOk} yt=${p.ytOk} err=${p.error}")
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        var last: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
                if (last != null && last != network) verifySoon(3000)
                last = network
                setUnderlyingNetworks(arrayOf(network))
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    private fun teardown(error: String?) {
        checkJob?.cancel()
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        TProxyService.stop()
        core?.stop(); core = null
        byeDpi?.close(); byeDpi = null; Tunnel.byeDpiPort = null
        runCatching { tun?.close() }; tun = null
        if (error != null) {
            Tunnel.status.value = if (error.isEmpty()) Tunnel.Status() else Tunnel.Status(Tunnel.State.ERROR, error)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun foreground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("VLESS Card").setContentText(text)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }
}
