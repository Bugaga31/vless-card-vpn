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
import com.vlesscardvpn.core.DpiSet
import com.vlesscardvpn.core.DpiStrategies
import com.vlesscardvpn.core.Net
import com.vlesscardvpn.BuildConfig
import com.vlesscardvpn.xray.SocksAuth
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
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
    private var dpi: DpiSet? = null
    private var checkJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    /** Self-healing step after failed checks (0 = healthy); reset by a successful check or a user (re)connect. */
    private var healStep = 0
    /** Auto mode gave up on servers for this connection: ByeDPI only. */
    private var autoDpiOnly = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { scope.launch { lock.withLock { teardown("") } ; stopSelf() } }
            else -> { // ACTION_START or always-on restart (null intent)
                foreground("Подключение…")
                healStep = 0; autoDpiOnly = false
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
            val network = Net.key(this)
            var settings = st.settings
            var selected = st.selected
            if (settings.mode != Mode.BYEDPI && selected.isEmpty()) {
                // Nothing chosen: take the best servers that passed the last test.
                selected = st.servers.filter { st.state(it).works }.sortedBy { st.state(it).realMs }.take(5)
            }
            if (settings.mode == Mode.AUTO) {
                settings = settings.copy(mode = if (selected.isEmpty() || autoDpiOnly) Mode.BYEDPI else Mode.SERVERS)
                if (settings.mode == Mode.BYEDPI) selected = emptyList()
            }
            if (settings.mode != Mode.BYEDPI && selected.isEmpty())
                error("Нет выбранных серверов. Откройте «Серверы», нажмите «Проверить» и отметьте рабочие.")
            selected = Actions.pinCertificates(selected)
            val rotate = settings.rotateMasks
            val withMasks = selected.map { srv ->
                val ss = st.state(srv)
                val mine = ss.maskFor(network)
                val id = if (rotate && ss.goodMasks.isNotEmpty()) (ss.goodMasks + mine).filter { it.isNotEmpty() }.random() else mine
                srv to Masks.byId(id)
            }
            val needCurrent = settings.mode != Mode.SERVERS || withMasks.any { it.second?.dpi == Masks.CURRENT_DPI }
            val fixed = if (settings.mode == Mode.BYEDPI) emptySet() else Masks.strategies(withMasks.map { it.second }) - Masks.CURRENT_DPI
            val set = DpiSet(this); dpi = set
            set.start(settings, network, needCurrent, fixed, allowLocal = BuildConfig.DEBUG)
            val bdPort = set.currentPort
            Tunnel.byeDpiPort = bdPort
            val dpiLabel = set.current?.label.orEmpty()
            Tunnel.dpiLabel = dpiLabel
            val proxy = settings.proxyOnly
            // Proxy mode: other apps must find the proxy, so the fixed port without a password (only on 127.0.0.1 unless shared).
            val socks = if (settings.stealthSocks && !proxy) SocksAuth(randomPort(), randomToken(), randomToken()) else SocksAuth(settings.socksPort)
            val config = XrayConfigBuilder.vpnConfig(withMasks, settings, bdPort, socks, set.ports,
                listen = if (proxy && settings.lanShare) "0.0.0.0" else "127.0.0.1", httpPort = if (proxy) settings.httpPort else null)
            val c = XrayCore.Instance("vpn"); core = c
            c.start(config)
            check(c.running) { "Xray не запустился: ${c.lastStatus}" }
            Tunnel.socks = socks
            Log.i("E2E", "socks port=${socks.port} auth=${socks.auth} dpi=${dpiLabel.ifEmpty { "-" }} fixed=${set.ports.keys} mode=${settings.mode} services=${settings.services} proxy=$proxy")
            if (!proxy) startTun(settings, socks)

            val auto = st.settings.mode == Mode.AUTO
            var route = when (settings.mode) {
                Mode.BYEDPI -> "Без сервера · $dpiLabel"
                Mode.HYBRID -> "${selected.size} серв. + $dpiLabel для YouTube/Discord"
                else -> if (selected.size == 1) selected[0].name else "${selected.size} серверов · ${settings.balance.title.lowercase()}"
            }
            if (auto) route = "Авто · $route"
            if (settings.services.isNotEmpty()) route += " · через VPN только " + com.vlesscardvpn.core.Services.label(settings.services)
            if (proxy) route = "Прокси SOCKS5 :${socks.port} · HTTP :${settings.httpPort}" + (if (settings.lanShare) " (для всей сети: ${lanIp() ?: "IP телефона"})" else "") + " · $route"
            Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTED, "Подключено", route, "Проверяю интернет…", null, System.currentTimeMillis())
            foreground(if (settings.quietNotification) "Активно" else "Подключено · $route")
            watchNetwork()
            verifySoon(0)
        } catch (e: Throwable) {
            Log.w(TAG, "connect failed", e)
            Log.i("E2E", "connect failed: ${e.message}")
            teardown(e.message ?: e.javaClass.simpleName)
            stopSelf()
        }
    }

    private fun startTun(settings: com.vlesscardvpn.model.Settings, socks: SocksAuth) {
        val b = Builder().setSession("VLESS Card").setMtu(MTU)
            .addAddress(IPV4, 30).addRoute("0.0.0.0", 0)
            .addAddress(IPV6, 126).addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        val apps = settings.apps.filter { it != packageName }
        if (!settings.perApp) b.addDisallowedApplication(packageName)
        else if (settings.onlyApps && apps.isNotEmpty()) apps.forEach { runCatching { b.addAllowedApplication(it) } }
        else { b.addDisallowedApplication(packageName); apps.forEach { runCatching { b.addDisallowedApplication(it) } } }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val fd = b.establish() ?: error("Система не дала создать VPN (нет разрешения)")
        tun = fd
        TProxyService.start(filesDir, fd, socks.port, MTU, IPV4, IPV6, socks.user, socks.pass)
    }

    private fun lanIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    /** End-to-end check through the running chain (app → 127.0.0.1:socks → Xray → server → internet). */
    private fun verifySoon(delayMs: Long) {
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(delayMs)
            val st = Store.state.value.settings
            val port = Tunnel.socks?.port ?: return@launch
            val p = Tester.probeSocks(port, st.testUrl)
            val cur = Tunnel.status.value
            if (cur.state != Tunnel.State.CONNECTED) return@launch
            val text = if (p.realMs > 0) "Интернет работает · ${p.realMs} мс · 256 КБ: ${Actions.yn(p.bigOk)} · YouTube: ${Actions.yn(p.ytOk)} · Telegram: ${Actions.yn(p.tgOk)}"
                else "Нет ответа через выбранный маршрут (${p.error}). Проверьте серверы или включите маскировку."
            Tunnel.status.value = cur.copy(check = text, checkOk = p.works)
            Log.i("E2E", "check ok=${p.works} ms=${p.realMs} big=${p.bigOk} yt=${p.ytOk} tg=${p.tgOk} err=${p.error}")
            if (p.works) { healStep = 0; scheduleOptimize() } else heal()
        }
    }

    private var optJob: kotlinx.coroutines.Job? = null
    /**
     * Background speed-up while connected. Adaptive: first pass 2 min after a good check; after a switch the next
     * one comes in 5 min (confirm the new choice), and while nothing changes the interval grows 10 → 20 → 30 min,
     * so a stable connection is left alone and the battery is spared. A network change restarts the cycle.
     */
    private fun scheduleOptimize(firstDelay: Long = 120_000) {
        if (optJob?.isActive == true) return
        optJob = scope.launch {
            delay(firstDelay)
            var calm = 0
            while (Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                val reconnect = runCatching { Actions.optimize() }.getOrDefault(false)
                if (reconnect && Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                    Log.i("E2E", "optimize: switching to faster servers")
                    lock.withLock { connect() }
                }
                calm = if (Actions.lastOptimizeSwitched) 0 else minOf(calm + 1, 3)
                // sleep until the next pass, or wake early (45 s) when the network changes
                if (kotlinx.coroutines.withTimeoutOrNull(longArrayOf(5, 10, 20, 30)[calm] * 60_000L) { optKick.receive() } != null) { calm = 0; delay(45_000) }
            }
        }
    }

    /** New network (Wi-Fi ↔ mobile): other servers may be faster here — re-run the speed-up soon. */
    private val optKick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private fun optimizeSoon() {
        if (optJob?.isActive == true) optKick.trySend(Unit) else scheduleOptimize(firstDelay = 45_000)
    }

    /**
     * Masking got blocked (or the server died): 1) next good masks, 2) a fresh mask search for the selected servers,
     * 3) Auto mode only — ByeDPI without servers. Each step reconnects by itself.
     */
    private fun heal() {
        val st = Store.state.value
        if (!st.settings.autoHeal || st.settings.mode == Mode.BYEDPI || autoDpiOnly) return
        val servers = st.selected.ifEmpty { st.servers.filter { st.state(it).works }.sortedBy { st.state(it).realMs }.take(5) }
        healStep++
        val cur = Tunnel.status.value
        scope.launch {
            when {
                healStep == 1 && Actions.nextMasks(servers) -> Tunnel.status.value = cur.copy(check = "Маскировку, похоже, распознали — переключаюсь на другую…", checkOk = null)
                healStep <= 2 -> {
                    healStep = 2
                    Tunnel.status.value = cur.copy(check = "Подбираю новую маскировку для серверов…", checkOk = null)
                    runCatching { Actions.doFindMasks(servers, 24) }
                }
                st.settings.mode == Mode.AUTO -> {
                    autoDpiOnly = true
                    Tunnel.status.value = cur.copy(check = "Серверы не отвечают — перехожу на обход DPI без сервера…", checkOk = null)
                }
                else -> return@launch
            }
            Log.i("E2E", "heal step=$healStep")
            delay(500)
            lock.withLock { connect() }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        var last: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
                val changed = last != null && last != network
                last = network
                setUnderlyingNetworks(arrayOf(network))
                if (!changed) return
                // Another network (Wi-Fi ↔ mobile): its own DPI strategy may be remembered — restart with it.
                val st = Store.state.value.settings
                val cur = dpi?.current
                val masksDiffer = Store.state.value.let { a -> a.selected.any { a.state(it).netMasks.isNotEmpty() } }
                if (cur != null && DpiStrategies.resolve(st, Net.key(this@TunnelService)).id != cur.id || masksDiffer) {
                    scope.launch { delay(1500); lock.withLock { connect() } }
                } else verifySoon(3000)
                if (Store.state.value.settings.autoOptimize) optimizeSoon()
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    private fun teardown(error: String?) {
        checkJob?.cancel()
        if (error != null) optJob?.cancel()
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        TProxyService.stop()
        core?.stop(); core = null
        dpi?.close(); dpi = null; Tunnel.byeDpiPort = null; Tunnel.socks = null
        runCatching { tun?.close() }; tun = null
        if (error != null) {
            Tunnel.status.value = if (error.isEmpty()) Tunnel.Status() else Tunnel.Status(Tunnel.State.ERROR, error)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun randomPort(): Int {
        val rnd = SecureRandom()
        repeat(20) {
            val p = 20000 + rnd.nextInt(40000)
            if (runCatching { ServerSocket(p, 1, InetAddress.getByName("127.0.0.1")).close() }.isSuccess) return p
        }
        return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }

    private fun randomToken(): String {
        val abc = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = SecureRandom()
        return String(CharArray(20) { abc[rnd.nextInt(abc.length)] })
    }

    private fun foreground(text: String) {
        val quiet = Store.state.value.settings.quietNotification
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(if (quiet) android.R.drawable.stat_notify_sync_noanim else android.R.drawable.ic_lock_lock)
            .setContentTitle(if (quiet) "Синхронизация" else "VLESS Card").setContentText(if (quiet) "Активно" else text)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }
}
