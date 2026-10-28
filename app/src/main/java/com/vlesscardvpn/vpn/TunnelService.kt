package com.vlesscardvpn.vpn

import kotlinx.coroutines.ensureActive
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
import android.net.NetworkRequest
import android.net.LinkProperties
import com.vlesscardvpn.core.NetworkHandoff
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
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
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
        const val ACTION_PAUSE = "com.vlesscardvpn.PAUSE"
        const val PAUSE_MS = 5 * 60_000L
        val WARM = listOf("www.youtube.com", "i.ytimg.com", "yt3.ggpht.com", "rr1---sn-4g5e6nzz.googlevideo.com", "www.google.com",
            "play.googleapis.com", "web.telegram.org", "www.instagram.com", "scontent.cdninstagram.com", "discord.com", "chatgpt.com", "x.com")
        /** Compatibility ceiling; actual MTU is selected per underlying network. */
        const val TURBO_MTU = 1400
        private const val CHANNEL = "vpn"
        private const val TAG = "TunnelService"
        const val MTU = 1500
        const val IPV4 = "10.10.14.1"
        const val IPV6 = "fd66:6c65:7373::1"

        fun start(context: Context) {
            if (BuildConfig.DEBUG) Log.i("E2E", "start request caller=${com.vlesscardvpn.core.ConnectDiagnostics.startCaller(Throwable().stackTrace)}")
            val i = Intent(context, TunnelService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun pause(context: Context) { context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_PAUSE)) }
        fun stop(context: Context) {
            context.startService(Intent(context, TunnelService::class.java).setAction(ACTION_STOP))
        }
    }

    /** A failure in a background job (check, heal, speed-up) must never take the whole app down. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w(TAG, "background job failed", e) })
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var core: XrayCore.Instance? = null
    private var dpi: DpiSet? = null
    private var checkJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val handoff = NetworkHandoff()
    private var networkRefreshJob: Job? = null
    private val networkSignal = java.util.concurrent.atomic.AtomicLong()

    /** Self-healing step after failed checks (0 = healthy); reset by a successful check or a user (re)connect. */
    private var healStep = 0
    /** Servers of the running connection (selected or the best ones taken automatically). */
    @Volatile private var usedTorRoute: Boolean = false
    private var chainJob: Job? = null
    @Volatile private var chainMeasuring = false
    private val chainEpoch = java.util.concurrent.atomic.AtomicLong()
    private var chainChoice: com.vlesscardvpn.core.AutoChainRunner.Measured? = null
    @Volatile private var usedChain: com.vlesscardvpn.core.AutoChainRunner.Choice? = null
    private val chainReserves = com.vlesscardvpn.core.AutoChainReserves()
    private var chainScanAfter = 0L
    @Volatile private var usedVariants: List<Pair<com.vlesscardvpn.model.Server,com.vlesscardvpn.xray.Mask?>> = emptyList()
    private fun cancelChainSearch() { chainReserves.clear(); chainEpoch.incrementAndGet(); chainJob?.cancel(); chainJob=null; chainChoice=null }
    @Volatile private var usedServers: List<com.vlesscardvpn.model.Server> = emptyList()
    @Volatile private var appliedAutoWeights = ""
    @Volatile private var warpChained = false
    /** DPI-strategy repairs in a row (reset by a good check). */
    private var dpiHeals = 0
    /** Connect attempts that failed to start (Auto mode retries with safer settings). */
    private var connectFails = 0
    private var maintenanceJob: Job? = null
    private var connectedElapsed = 0L
    /** Retry after a failed start: links' own settings, no masks. */
    private var safeMasks = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) {
            val command = when { intent?.getBooleanExtra("e2e_chain_check",false)==true -> "chain"; intent?.action==ACTION_STOP -> "stop"; intent?.action==ACTION_PAUSE -> "pause"; intent?.action==ACTION_START -> "start"; else -> "sticky" }
            Log.i("E2E", "service command=$command state=${Tunnel.status.value.state}")
        }
        cancelChainSearch()
        if(BuildConfig.DEBUG && intent?.getBooleanExtra("e2e_chain_check",false)==true) {
            scope.launch {
                kotlinx.coroutines.withTimeoutOrNull(60_000) {while(Actions.running.value)delay(100)}
                lock.withLock {
                try {
                    val set=dpi ?: error("No running DPI set")
                    val settings=Store.state.value.settings
                    val epoch=chainEpoch.get()
                    val observed=core
                    val net=Net.key(this@TunnelService)
                    val handle=Net.underlying(this@TunnelService)?.toString()
                    fun generation():Long=if(core===observed && Net.key(this@TunnelService)==net && Net.underlying(this@TunnelService)?.toString()==handle)chainEpoch.get() else Long.MIN_VALUE
                    set.warmCurrent(settings,net,true)
                    val measured=com.vlesscardvpn.core.RuntimeChainE2E.measure(settings,Store.state.value.servers,set,net,epoch,::generation)
                    check(measured!=null && generation()==epoch){"No verified DPI-server winner"}
                    chainChoice=measured
                    connect(soft=true)
                    check(usedChain?.chain?.layout==com.vlesscardvpn.core.AutoChainSelection.Layout.DPI_SERVER){"Measured chain was not applied"}
                    Log.i("E2E","auto-chain-e2e applied=DPI_SERVER body=${measured.sample.bodyBytes} data=${measured.sample.data}")
                }catch(e:kotlinx.coroutines.CancellationException){throw e}
                 catch(e:Exception){Log.i("E2E","auto-chain-e2e FAIL ${e.javaClass.simpleName}"); Log.i("E2E", com.vlesscardvpn.core.ConnectDiagnostics.describe("chain","chain_check",e))}
            }}
            return START_STICKY
        }
        if (intent?.action != ACTION_PAUSE) { pauseJob?.cancel(); Tunnel.pausedUntil.value = 0 }
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { lock.withLock { pause() } }
            ACTION_STOP -> { scope.launch { lock.withLock { teardown("") } ; stopSelf() } }
            else -> { // ACTION_START or always-on restart (null intent)
                foreground("Подключение…")
                healStep = 0; dpiHeals = 0; connectFails = 0; safeMasks = false
                scope.launch { lock.withLock { connect() } }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() { cancelChainSearch(); scope.launch { lock.withLock { teardown("VPN отключён системой или другим VPN") }; stopSelf() } }

    private var pauseJob: Job? = null
    /** «Пауза 5 минут» (for a bank or a game that hates VPNs): VPN off, the service waits and switches it back on by itself. */
    private fun pause() {
        teardown(null)
        val until = System.currentTimeMillis() + PAUSE_MS
        Tunnel.pausedUntil.value = until
        val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(until))
        Tunnel.status.value = Tunnel.Status(Tunnel.State.IDLE, "Пауза — VPN включится сам в $at")
        foreground("Пауза до $at", paused = true)
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(PAUSE_MS)
            if (Tunnel.pausedUntil.value != 0L) { Tunnel.pausedUntil.value = 0; lock.withLock { connect() } }
        }
    }

    /** Screen off or battery saver (with «Ускорение»): no background probes — they wake the radio and drain the battery. */
    private fun asleep(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return false
        return !pm.isInteractive && Store.state.value.settings.quietScreen
    }

    private var screenReceiver: android.content.BroadcastReceiver? = null
    /** Phone unlocked after sleeping: one light check, so a connection that died at night is fixed before the user notices. */
    private fun watchScreen() {
        if (screenReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (Tunnel.status.value.state != Tunnel.State.CONNECTED || healJob?.isActive == true) return
                scope.launch {
                    delay(1500)
                    val port = Tunnel.socks?.port ?: return@launch
                    val ok = Tester.probeSocks(port, Store.state.value.settings.testUrl, big = false, youtube = false, attempts = 1).realMs > 0
                    Log.i("E2E", "screen on: check ok=$ok")
                    if (!ok) verifySoon(0) else { watchdog(); coverTraffic(); torLoop() }
                }
            }
        }
        runCatching { registerReceiver(r, android.content.IntentFilter(Intent.ACTION_USER_PRESENT)); screenReceiver = r }
    }

    override fun onDestroy() {
        cancelChainSearch()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }; screenReceiver = null
        if (Tunnel.status.value.state == Tunnel.State.CONNECTED) runCatching { com.vlesscardvpn.core.Traffic.tick() }
        // Never block the main thread on a long connect/heal holding the lock (that was an ANR): wait 1.5 s at most.
        runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(1500) { lock.withLock { teardown(null) } } ?: teardown(null) } }
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }; netCallback = null
        scope.cancel()
        com.vlesscardvpn.tor.TorCard.stopEmbedded()
        super.onDestroy()
    }

    /**
     * [soft]: internal reconnect (network change, healing, speed-up) — the TUN and its local proxy stay, only Xray and
     * the DPI engines restart. No moment without VPN: apps don't leak past it and don't drop everything on "VPN off".
     */
    private suspend fun connect(soft: Boolean = false) {
        if (!soft) chainChoice = null
        val prevSocks = Tunnel.socks
        val keepTun = soft && tun != null && prevSocks != null
        lastSoft = keepTun
        teardown(null, keepTun)
        Store.init(this); Actions.init(this)
        val prev = Tunnel.status.value
        Tunnel.status.value = if (keepTun && prev.state == Tunnel.State.CONNECTED) prev.copy(check = "Переподключаюсь без разрыва VPN…", checkOk = null)
            else Tunnel.Status(Tunnel.State.CONNECTING, "Подключение…")
        var connectPhase = "init"
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) { XrayCore.init(this@TunnelService) }
            connectPhase = "selection"
            val st = Store.state.value
            watchNetwork()
            val network = Net.key(this)
            var settings = com.vlesscardvpn.tor.TorConnectionPolicy.runtime(
                com.vlesscardvpn.tor.TorNetworkRecommendations.apply(st.settings, Net.family(network)))
            val automatic = settings.mode == Mode.AUTO
            var selected = st.selected.filter(com.vlesscardvpn.core.AutoEligibility::allowed)
            if (selected.isEmpty()) {
                // Nothing chosen: take the best servers that passed the last test.
                val candidates = Actions.preferred(st, st.servers.filter { st.state(it).works })
                selected = if (automatic && !com.vlesscardvpn.core.GameSession.active()) candidates.sortedWith(
                    com.vlesscardvpn.core.ServerChoice.downloadOrder({ st.state(it) },network,System.currentTimeMillis())).take(10)
                else candidates.sortedBy { st.state(it).score }.take(10)
                    .let { l -> l.take(Actions.closeOnes(l.map { st.state(it).score })) }
            }
            if (settings.mode == Mode.AUTO) {
                // Retain eight working outbounds, including routes discovered after the first success.
                // Xray's selected balancer distributes connections; this is not bandwidth bonding.
                val candidates = Actions.preferred(st, (selected + st.servers.filter { st.state(it).works }).distinctBy { it.id })
                val usable = com.vlesscardvpn.core.AutoEligibility.servicePool(candidates,{st.state(it)},network,System.currentTimeMillis())
                selected = com.vlesscardvpn.core.AutoEligibility.connectedPool(usable,{st.state(it)},network,
                    System.currentTimeMillis(),com.vlesscardvpn.core.GameSession.active())
                settings = settings.copy(mode = Mode.SERVERS)
            }
            // Xray round-robin treats an outbound with no observation yet as alive.
            // Exclude only explicit negative TCP hints before exposing a manual pool;
            // keep timeouts/unknown, and never turn this hint into working/PASS evidence.
            // Auto already uses measured eligibility; do not extend its search budget.
            if (!automatic && selected.size > 1) {
                val handle = Net.underlying(this)?.toString()
                val hints = Tester.fastFilter(selected.take(16), budgetMs = 600)
                check(Net.key(this) == network && Net.underlying(this)?.toString() == handle) { "Network changed during pool startup" }
                selected = com.vlesscardvpn.core.ManualPoolFilter.retain(selected, { it.id }, hints)
            }
            val measuredChain = chainChoice?.takeIf { measured ->
                com.vlesscardvpn.core.AutoChainRunner.fresh(measured, st.settings, network, chainEpoch.get(), android.os.SystemClock.elapsedRealtime()) &&
                com.vlesscardvpn.core.AutoChainLivePolicy.enabled(st.settings) &&
                (measured.choice.server == null || st.servers.any { it.toJson().toString() == measured.choice.server.toJson().toString() })
            }
            if (measuredChain == null) chainChoice = null
            if (measuredChain != null) {
                selected = listOfNotNull(measuredChain.choice.server)
                settings = settings.copy(mode=Mode.SERVERS, torAll = settings.torAll || measuredChain.choice.chain.layout.tor)
            }
            if (com.vlesscardvpn.BuildConfig.DEBUG) Log.i("E2E",
                "selection automatic=$automatic catalog=${st.servers.size} stored_selected=${st.selected.size} working=${st.servers.count { st.state(it).works }} eligible=${selected.size}")
            if (selected.isEmpty()) error("Нет рабочего VLESS-сервера. Прямой режим недоступен; нажмите «Авто» или обновите серверы.")
            selected = Actions.pinCertificates(com.vlesscardvpn.core.AutoEligibility.launchPool(selected, automatic))
            usedServers = selected
            val rotate = settings.rotateMasks
            val withMasks = measuredChain?.choice?.let { choice ->
                choice.server?.let { server -> listOf(server to if(choice.chain.layout == com.vlesscardvpn.core.AutoChainSelection.Layout.DPI_SERVER)
                    (choice.mask ?: com.vlesscardvpn.xray.Mask("auto-chain-dpi","Auto DPI","")).copy(dpi=choice.dpiStrategy) else choice.mask) } ?: emptyList()
            } ?: selected.map { srv ->
                val ss = st.state(srv)
                val mine = ss.maskFor(network)
                // rotate only among masks no heavier than the measured one (a tiny-packet mask would slow YouTube down)
                val limit = Masks.byId(mine)?.let { Masks.cost(it) } ?: 0
                val pool = (ss.goodMasks + mine).filter { it.isNotEmpty() && (Masks.byId(it)?.let { m -> Masks.cost(m) } ?: 0) <= limit }
                val id = com.vlesscardvpn.core.AutoRoutePolicy.launchMask(automatic, rotate, mine, pool) { it.random() }
                srv to if (safeMasks) null else Masks.byId(id)
            }
            val needCurrent = withMasks.any { it.second?.dpi == Masks.CURRENT_DPI }
            val fixed = Masks.strategies(withMasks.map { it.second }) - Masks.CURRENT_DPI
            connectPhase = "dpi"
            val set = DpiSet(this); dpi = set
            set.start(settings, network, needCurrent, fixed, allowLocal = BuildConfig.DEBUG)
            set.watch(scope)
            if (measuredChain?.choice?.chain?.layout == com.vlesscardvpn.core.AutoChainSelection.Layout.DPI_SERVER) {
                val choice=measuredChain.choice
                val actual=if(choice.dpiStrategy == Masks.CURRENT_DPI) set.current else DpiStrategies.byId(choice.dpiStrategy,settings)
                check(actual != null && com.vlesscardvpn.core.AutoChainRunner.digest(actual.toString()+"|"+settings.byeDpiSni) == choice.chain.dpiKey) { "Measured DPI strategy changed" }
            }
            val bdPort = set.currentPort
            Tunnel.byeDpiPort = bdPort
            val dpiLabel = set.current?.label.orEmpty()
            Tunnel.dpiLabel = dpiLabel
            val proxy = settings.proxyOnly
            // Proxy mode: other apps must find the proxy, so the fixed port without a password (only on 127.0.0.1 unless shared).
            val socks = if (keepTun && prevSocks != null) prevSocks else if (settings.stealthSocks && !proxy) SocksAuth(randomPort(), randomToken(), randomToken()) else SocksAuth(settings.socksPort)
            // «WARP через сервер»: WARP account from the list, the ordinary servers carry it
            val warpHop = if (measuredChain == null && settings.warpChain && withMasks.any { it.first.protocol != "wireguard" }) st.servers.firstOrNull { Masks.isWarp(it) } else null
            warpChained = warpHop != null
            val used = if (warpHop != null) withMasks.filter { it.first.protocol != "wireguard" } else withMasks
            val downloadSpeeds = if (automatic && !com.vlesscardvpn.core.GameSession.active()) used.map {
                com.vlesscardvpn.core.ServerChoice.measuredSpeed(st.state(it.first),network,System.currentTimeMillis()) } else emptyList()
            val downloadLatencies = used.map { st.state(it.first).realMs }
            connectPhase = "config"
            val config = XrayConfigBuilder.vpnConfig(used, settings, bdPort, socks, set.ports,
                listen = if (proxy && settings.lanShare) "0.0.0.0" else "127.0.0.1", httpPort = if (proxy) settings.httpPort else null, warpHop = warpHop,
                heavy = XrayConfigBuilder.heavyOf(used.map { if (automatic) com.vlesscardvpn.core.ServerChoice.measuredSpeed(st.state(it.first),network,System.currentTimeMillis()) else st.state(it.first).kbps },
                    used.map { if (automatic && com.vlesscardvpn.core.AutoEligibility.serviceRank(st.state(it.first),network,System.currentTimeMillis()) == 1) null else st.state(it.first).ytOk }),
                serviceRoutes = if (measuredChain == null) com.vlesscardvpn.core.ServiceRoutes.snapshot(network, android.os.SystemClock.elapsedRealtime()) else emptyMap(),
                quietLogs = !com.vlesscardvpn.core.PhonePower.refresh(this).allowLight,
                downloadSpeeds = downloadSpeeds, downloadLatencies = downloadLatencies)
            val c = XrayCore.Instance("vpn"); core = c
            Tunnel.wireguardKeys = (used.map { it.first } + listOfNotNull(warpHop)).filter { it.protocol == "wireguard" }.map { it.secret }.toSet()
            connectPhase = "core"
            c.start(config)
            check(c.running) { "Xray не запустился: ${c.lastStatus}" }
            usedServers = used.map { it.first }; usedVariants=used
            usedChain = measuredChain?.choice
            usedTorRoute = com.vlesscardvpn.tor.TorRoutePolicy.active(settings) || measuredChain?.choice?.chain?.layout?.tor == true
            appliedAutoWeights = com.vlesscardvpn.core.SpeedBalance.fingerprint(downloadSpeeds,downloadLatencies)
            Tunnel.socks = socks
            if (BuildConfig.DEBUG) Log.i("E2E", "pool size=${used.size} ids=${used.map { it.first.id }}")
            Log.i("E2E", "socks port=${socks.port} auth=${socks.auth} dpi=${dpiLabel.ifEmpty { "-" }} fixed=${set.ports.keys} mode=${settings.mode} services=${settings.services} proxy=$proxy")
            connectPhase = "tun"
            if (!proxy && !keepTun) startTun(settings, socks)

            val auto = com.vlesscardvpn.tor.TorConnectionPolicy.needsAutoSearch(st.settings)
            var route = if (selected.size == 1) selected[0].name else "${selected.size} серверов · ${settings.balance.title.lowercase()}"
            if (measuredChain != null) route = when (measuredChain.choice.chain.layout) {
                com.vlesscardvpn.core.AutoChainSelection.Layout.SERVER_TOR,
                com.vlesscardvpn.core.AutoChainSelection.Layout.DPI_SERVER_TOR -> "${selected.first().name} → Tor · через VLESS"
                com.vlesscardvpn.core.AutoChainSelection.Layout.DPI_SERVER -> "ByeDPI → ${selected.first().name}"
                else -> selected.firstOrNull()?.name ?: "Измеренная цепочка"
            } else if (settings.torAll) route += " → Tor через VLESS · UDP через VLESS"
            else if (usedTorRoute) route += " · выбранные TCP-сервисы через Tor/VLESS"
            if (auto) route = "Авто · $route"
            if (proxy) route = "Прокси SOCKS5 :${socks.port} · HTTP :${settings.httpPort}" + (if (settings.lanShare) " (для всей сети: ${lanIp() ?: "IP телефона"})" else "") + " · $route"
            val since = if (keepTun && prev.since > 0) prev.since else System.currentTimeMillis()
            if (!keepTun) { com.vlesscardvpn.core.Traffic.start(); Tunnel.rx0 = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()); Tunnel.tx0 = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()) }
            connectedElapsed = android.os.SystemClock.elapsedRealtime()
            Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTED, "Подключено", route, "Проверяю интернет…", null, since)
            watchScreen()
            foreground(if (settings.quietNotification) "Активно" else "Подключено · $route")
            connectPhase = "notification"
            liveNotification(route)
            watchNetwork()
            torLoop()
            verifySoon(0)
            // Instant start: DNS and first TLS handshakes fire at once, in parallel — not after the verify.
            scope.launch { kotlinx.coroutines.delay(250); runCatching { warmUp() } }
            scope.launch { kotlinx.coroutines.delay(250); runCatching { com.vlesscardvpn.core.Warmup.warm(socks.port, com.vlesscardvpn.core.Warmup.plan(settings)) } }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            chainChoice = null; usedChain = null
            if (BuildConfig.DEBUG) Log.i("E2E", com.vlesscardvpn.core.ConnectDiagnostics.describe("connect",connectPhase,e))
            Log.w(TAG, "connect failed", e)
            Log.i("E2E", "connect failed: ${e.message}")
            if (keepTun) {
                if (connectFails == 0) {
                    connectFails = 1
                    safeMasks = true
                    teardown(null, keepTun = true)
                    Tunnel.status.value = prev.copy(check = "VLESS не запустился (${com.vlesscardvpn.core.Errors.human(e)}); повторяю с безопасной маскировкой. Трафик остаётся заблокированным до сервера.", checkOk = false)
                    connect(soft = true)
                    return
                }
                safeMasks = false
                teardown(null, keepTun = true)
                Tunnel.status.value = prev.copy(state = Tunnel.State.CONNECTED, message = "Защита VPN включена",
                    check = "VLESS не восстановлен (${com.vlesscardvpn.core.Errors.human(e)}). Трафик заблокирован; ищу рабочий сервер.",
                    checkOk = false)
                foreground("Трафик заблокирован · поиск VLESS")
                if (Store.state.value.settings.mode == Mode.AUTO) recoverServerRoute() else heal()
                return
            }
            if (com.vlesscardvpn.tor.TorConnectionPolicy.needsAutoSearch(Store.state.value.settings) && connectFails < 2) {
                connectFails++
                if (connectFails == 1) {
                    safeMasks = true
                    teardown(null)
                    Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Подключение…", check = "Повторяю с безопасной маскировкой VLESS…")
                    connect(); return
                }
                safeMasks = false
                val found = runCatching { Store.busy { Actions.autoRescue(usedServers, 1) } }.getOrDefault(0)
                if (found > 0) {
                    connectFails = 2
                    teardown(null)
                    Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Подключение…", check = "Проверенный резервный VLESS-сервер найден…")
                    connect(); return
                }
            }
            teardown(if (e is IllegalStateException) e.message ?: com.vlesscardvpn.core.Errors.human(e) else com.vlesscardvpn.core.Errors.human(e))
            stopSelf()
        }
    }

    /** 10.10.14.1 → 10.10.14.2: the other address of the tunnel's /30. */
    private fun dnsIn(v4: String) = v4.substringBeforeLast('.') + "." + ((v4.substringAfterLast('.').toIntOrNull() ?: 1) + 1)

    private fun startTun(settings: com.vlesscardvpn.model.Settings, socks: SocksAuth) {
        val v4 = if (settings.randomTun) com.vlesscardvpn.core.Disguise.tunV4() else IPV4
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = Net.underlying(this)
        val caps = cm?.getNetworkCapabilities(network)
        val mtu = com.vlesscardvpn.core.TransportPolicy.mtu(caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            warpChained || usedServers.any { Masks.isWarp(it) }, if (Build.VERSION.SDK_INT >= 29) cm?.getLinkProperties(network)?.mtu ?: 0 else 0)
        val b = Builder().setSession(if (settings.randomTun || settings.quietNotification) "Sync" else "VLESS Card").setMtu(mtu)
            .addAddress(v4, 30).addRoute("0.0.0.0", 0)
            // No IPv6 in the tunnel: Android then refuses IPv6 at once and apps (Chrome) fall back to IPv4 instantly.
            // With an IPv6 route the local TUN «accepted» IPv6 connections and Xray dropped them — Chrome saw a
            // connection that died and showed «нет соединения» instead of retrying over IPv4.
            // DNS: an address inside the tunnel, not 1.1.1.1/8.8.8.8 — for those Chrome («Безопасный DNS») and Android
            // («Частный DNS») switch to their own encrypted DNS, which returns IPv6 addresses and skips the VPN's DNS.
            // Every query to port 53 goes to Xray's DNS anyway, whatever the address.
            .addDnsServer(dnsIn(v4))
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        val apps = settings.apps.filter { it != packageName }
        if (!settings.perApp) b.addDisallowedApplication(packageName)
        else if (settings.onlyApps && apps.isNotEmpty()) apps.forEach { runCatching { b.addAllowedApplication(it) } }
        else {
            b.addDisallowedApplication(packageName); apps.forEach { runCatching { b.addDisallowedApplication(it) } }
            // banks, Госуслуги, маркетплейсы: around the VPN — they see a normal Russian user (skipped if not installed)
            if (settings.ruAppsDirect) (com.vlesscardvpn.core.Disguise.RU_APPS - apps.toSet()).forEach { runCatching { b.addDisallowedApplication(it) } }
        }        // Отдельный путь Tor: демон Tor (Termux) исключается из туннеля — его цепочки идут в интернет
        // напрямую, своим шифрованием (или мостами), а не через VLESS; Tor остаётся жив даже при упавшем VPN.
        if (settings.torRouting && settings.torDirect) runCatching { b.addDisallowedApplication("com.termux") }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val fd = b.establish() ?: error("Система не дала создать VPN (нет разрешения)")
        tun = fd
        TProxyService.start(filesDir, fd, socks.port, mtu, v4, null, socks.user, socks.pass,
            !com.vlesscardvpn.core.PhonePower.refresh(this).allowLight)
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
            val auth = Tunnel.socks ?: return@launch
            val port = auth.port
            val sampledNet = Net.key(this@TunnelService)
            val sampledHandle = Net.underlying(this@TunnelService)?.toString()
            val sampledServers = usedServers.toList()
            val sampledTorRoute = usedTorRoute
            val attributeServers = usedChain == null && com.vlesscardvpn.tor.TorRoutePolicy.canAttributeServerEvidence(sampledTorRoute)
            val sampledCore = core
            val sampledChainEpoch = chainEpoch.get()
            val checkServices = st.mode == Mode.AUTO || Tunnel.ytViaDpi
            var p = Tester.probeSocks(port, st.testUrl, big = false, youtube = checkServices, attempts = 1, budgetMs = 5000)
            // right after connecting the first packets may still be on their way (WireGuard/WARP handshake, a slow
            // mobile network): one calm second try before calling the route bad and starting a heal or a reconnect
            if (!p.works) { delay(3000); if (Tunnel.socks?.port == port) p = Tester.probeSocks(port, st.testUrl, big = false, youtube = checkServices, attempts = 1, budgetMs = 5000) }
            // A SOCKS HTTPS success uses remote name resolution and does not prove
            // Android apps' UDP DNS path. Never show a healthy VPN on that evidence alone.
            val dnsOk = if (p.works && !st.proxyOnly) com.vlesscardvpn.core.CancellableProbe.run { bind ->
                Tester.dnsViaSocks(port, auth.user, auth.pass, budgetMs = 3000, onCancel = bind)
            } else st.proxyOnly
            if (p.works && !dnsOk) p = p.copy(realMs = 0, error = "DNS через туннель не отвечает")
            val chainExitRejected = if(usedChain?.chain?.layout?.tor == true) {
                val country=com.vlesscardvpn.core.CancellableProbe.run {bind -> Tester.exitCountry(port,1000,onCall={call->bind {call.cancel()}})}
                country == null || country == "RU"
            } else false
            val cur = Tunnel.status.value
            if (cur.state != Tunnel.State.CONNECTED || core !== sampledCore || chainEpoch.get()!=sampledChainEpoch || Net.key(this@TunnelService) != sampledNet ||
                Net.underlying(this@TunnelService)?.toString() != sampledHandle) return@launch
            // WARP: ask Cloudflare itself whether the traffic comes through WARP (warp=on / plus), so it's clear it works.
            val warp = if (attributeServers && p.realMs > 0 && (warpChained || usedServers.any { Masks.isWarp(it) }))
                when (Tester.warpTrace(port).let { t -> if (t == "plus" || t == "on" || t == "off") t else { delay(2000); Tester.warpTrace(port) } }) {
                "plus" -> " · WARP+: включён ✓"; "on" -> " · WARP: включён ✓"; "off" -> " · WARP: не через Cloudflare ✗"; else -> " · WARP: Cloudflare не ответил"
            } else ""
            if (Tunnel.status.value.state != Tunnel.State.CONNECTED || core !== sampledCore ||
                Net.key(this@TunnelService) != sampledNet || Net.underlying(this@TunnelService)?.toString() != sampledHandle) return@launch
            val text = if(chainExitRejected) "Выход Tor не подтверждён или недопустим — возвращаюсь к проверенному Auto" else if (p.realMs > 0) "HTTPS · ${p.realMs} мс · Видео YouTube: ${if(p.ytOk==true) "✓" else if(p.ytOk==false) "нет ответа" else "не проверен"} · Telegram MTProto: ${if(p.tgOk==true) "✓" else if(p.tgOk==false) "нет ответа" else "не проверен"}$warp"
                else "Нет ответа через выбранный маршрут (${p.error}). " + if (st.mode == Mode.AUTO && st.autoHeal) "Ищу рабочий вариант сам…" else "Проверьте серверы или включите маскировку."
            Tunnel.status.value = cur.copy(check = text, checkOk = p.works && !chainExitRejected)
            if (attributeServers) runCatching { val stv = Store.state.value; com.vlesscardvpn.core.Brain.lived(sampledNet, sampledServers, { stv.state(it) }, p.works); com.vlesscardvpn.core.Brain.save(this@TunnelService) }
            Log.i("E2E", "check ok=${p.works} ms=${p.realMs} big=${p.bigOk} yt=${p.ytOk} tg=${p.tgOk} err=${p.error} warp=$warp dns=$dnsOk")
            if (attributeServers && p.works && st.mode == Mode.AUTO) Actions.rememberNet(sampledNet)
            // «Умный YouTube» broke YouTube here (the bypass stopped working): back to the servers for YouTube
            if (p.realMs > 0 && p.ytOk == false && Tunnel.ytViaDpi) {
                val net = Net.key(this@TunnelService)
                Store.update { a -> a.copy(settings = a.settings.copy(ytDpi = a.settings.ytDpi + (net to false))) }
                scope.launch { lock.withLock { connect(soft = true) } }
                return@launch
            }
            if((!p.works || chainExitRejected || p.tgOk==false || p.ytOk==false) && usedChain != null) {
                usedChain?.let {com.vlesscardvpn.core.AutoChainRunner.reject(it,sampledNet,android.os.SystemClock.elapsedRealtime(),
                    definiteFailure=!p.works || p.tgOk==false || p.ytOk==false)}
                chainChoice=chainReserves.take(st,sampledNet,chainEpoch.get(),android.os.SystemClock.elapsedRealtime(),usedChain?.chain?.id)
                usedChain=null; chainScanAfter=if(chainChoice == null) android.os.SystemClock.elapsedRealtime()+120_000 else 0L
                scope.launch { lock.withLock { connect(soft=true) } }
                return@launch
            }
            if (!p.works && lastSoft && Store.state.value.settings.autoHeal) {
                // a fresh Xray sometimes needs a moment: look again in 3 s before rebuilding the whole VPN
                // (every rebuild makes browsers show «нет сети» for a second — it must not happen by mistake)
                delay(3000)
                if (Tester.probeSocks(port, st.testUrl, big = false, youtube = false, attempts = 2).realMs > 0) { lastSoft = false; verifySoon(0); return@launch }
                if (!Actions.online()) { heal(); return@launch }
                // after an internal reconnect (network change, switching servers) the internet didn't come back: do what the
                // big button does (stop + start, the TUN rebuilt) before the longer healing — once per network change
                val sinceNet = System.currentTimeMillis() - netChangedAt
                if (sinceNet < 90_000L && !netHardDone) { netHardDone = true; hardRestart("новая сеть, а интернет не пошёл", force = true); return@launch }
                if (System.currentTimeMillis() - lastHard > 5 * 60_000L) { hardRestart("после переподключения интернет не пошёл"); return@launch }
            }
            if (p.works) { healStep = 0; dpiHeals = 0; connectFails = 0; scheduleOptimize(if (com.vlesscardvpn.core.Actions.deepPending || usedServers.size < com.vlesscardvpn.core.AutoEligibility.TOP) 5_000 else 60_000); com.vlesscardvpn.core.AppWatch.start(this@TunnelService, reconnect = { scope.launch { lock.withLock { connect(soft = true) } } }, maintenanceReconnect = { scheduleMaintenanceReconnect() }, connectedAt = { connectedElapsed }); watchdog(); standbyScout(); coverTraffic(); torLoop(); serviceRoutes(); autoChainScout() } else heal()
        }
    }

    /** Keep the working TUN while probing isolated alternatives. This, not a detached
     * policy object, applies the measured winner to the next live Xray config. */
    private fun autoChainScout() {
        if(chainJob?.isActive == true || !com.vlesscardvpn.core.AutoChainLivePolicy.enabled(Store.state.value.settings)) return
        chainJob=scope.launch(Dispatchers.IO) {
            delay(2_000)
            while(isActive && Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                val settings=Store.state.value.settings
                if(!com.vlesscardvpn.core.AutoChainLivePolicy.enabled(settings)) return@launch
                if(android.os.SystemClock.elapsedRealtime()<chainScanAfter || asleep() || com.vlesscardvpn.core.GameSession.active() ||
                    !com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService).let {it.allowLight && !it.deferTraffic} ||
                    healJob?.isActive==true || Actions.deepPending) {delay(30_000);continue}
                if(settings.protectActiveTraffic && userActive()) {delay(30_000);continue}
                var changed=false
                try { lock.withLock {
                    chainMeasuring=true
                    val observedCore=core ?: return@withLock
                    val net=Net.key(this@TunnelService)
                    val handle=Net.underlying(this@TunnelService)?.toString()
                    val epoch=chainEpoch.get()
                    val settingsKey=com.vlesscardvpn.core.AutoChainRunner.settingsKey(settings)
                    fun generation():Long = if(core===observedCore && Net.key(this@TunnelService)==net &&
                        Net.underlying(this@TunnelService)?.toString()==handle &&
                        com.vlesscardvpn.core.AutoChainRunner.settingsKey(Store.state.value.settings)==settingsKey) chainEpoch.get() else Long.MIN_VALUE
                    val set=dpi ?: return@withLock
                    kotlinx.coroutines.withTimeoutOrNull(2500) {set.warmCurrent(settings,net,BuildConfig.DEBUG)}
                    var torReady=false
                    if(settings.torAuto) {
                        torReady=kotlinx.coroutines.withTimeoutOrNull(1500) {
                            com.vlesscardvpn.core.CancellableProbe.run {bind ->
                                com.vlesscardvpn.tor.TorCard.controlEndpoints(this@TunnelService).any { endpoint ->
                                    val control=com.vlesscardvpn.tor.TorControl("127.0.0.1",endpoint.port,600)
                                    bind {control.close()}
                                    try {control.connect(endpoint.cookieHex);control.circuitEstablished()}catch(_:java.io.IOException){false}finally{control.close()}
                                }
                            }
                        } ?: false
                    }
                    val pool=ArrayList<com.vlesscardvpn.core.AutoChainRunner.Choice>()
                    val pairs=(usedVariants+Store.state.value.let {a -> a.servers.filter {a.state(it).works && com.vlesscardvpn.core.AutoEligibility.allowed(it)}
                        .take(2).map {it to Masks.byId(a.state(it).maskFor(net))}}).distinctBy {it.first.id}.take(2)
                    fun add(server:com.vlesscardvpn.model.Server?,mask:com.vlesscardvpn.xray.Mask?,layout:com.vlesscardvpn.core.AutoChainSelection.Layout,strategy:String="") {
                        val st=if(strategy==Masks.CURRENT_DPI)set.current else DpiStrategies.byId(strategy,settings)
                        val identity=st?.let {it.toString()+"|"+settings.byeDpiSni}.orEmpty()
                        pool.add(com.vlesscardvpn.core.AutoChainRunner.choice(server,mask,layout,settings,identity,strategy))
                    }
                    val current=usedChain ?: pairs.firstOrNull()?.let {(server,mask) ->
                        val st=if(mask?.dpi==Masks.CURRENT_DPI)set.current else mask?.dpi?.let {DpiStrategies.byId(it,settings)}
                        com.vlesscardvpn.core.AutoChainRunner.choice(server,mask,if(mask?.viaByeDpi==true) com.vlesscardvpn.core.AutoChainSelection.Layout.DPI_SERVER else com.vlesscardvpn.core.AutoChainSelection.Layout.SERVER,
                            settings,st?.let {it.toString()+"|"+settings.byeDpiSni}.orEmpty(),mask?.dpi.orEmpty())
                    }
                    current?.let(pool::add)
                    if(torReady) {
                        // Reserve a Tor challenger early, not behind six timed-out native trials.
                        pairs.firstOrNull {it.first.isTcpBased}?.let {(server,mask) -> add(server,mask,com.vlesscardvpn.core.AutoChainSelection.Layout.TOR_SERVER)}
                        add(null,null,com.vlesscardvpn.core.AutoChainSelection.Layout.TOR)
                    }
                    pairs.forEach {(server,mask) ->
                        if(server.isTcpBased && server.protocol in setOf("vless","vmess","trojan","shadowsocks","socks")) {
                            add(server,mask,com.vlesscardvpn.core.AutoChainSelection.Layout.SERVER)
                            if(set.currentPort!=null)add(server,mask,com.vlesscardvpn.core.AutoChainSelection.Layout.DPI_SERVER,Masks.CURRENT_DPI)
                        }
                    }
                    if(generation()!=epoch)return@withLock
                    val winner=com.vlesscardvpn.core.AutoChainRunner.compare(pool,settings,net,epoch,::generation,android.os.SystemClock::elapsedRealtime,
                        current?.chain?.id,set.currentPort,set.ports.toMap(),torReady,onMeasured={ measured ->
                            if(generation()==epoch) chainReserves.record(measured,settings,net,epoch,android.os.SystemClock.elapsedRealtime(),current?.chain?.id)
                        },onRejected={ rejected -> if(generation()==epoch) chainReserves.discard(rejected.chain.id) })
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if(winner!=null && generation()==epoch && Tunnel.status.value.state==Tunnel.State.CONNECTED) {
                        chainChoice=winner;changed=true
                        Log.i("E2E","auto-chain selected=${winner.choice.chain.layout} data=${winner.sample.data} tg=${winner.sample.telegram} yt=${winner.sample.youtube}")
                    }
                }
                } catch(e: kotlinx.coroutines.CancellationException) {throw e}
                  catch(e: Exception) {Log.i("E2E","auto-chain probe skipped: ${e.javaClass.simpleName}")}
                  finally {chainMeasuring=false}
                if(changed) {
                    // A separate job performs the soft switch: teardown cancels the scout itself.
                    scope.launch {lock.withLock {
                        if(chainChoice?.let {com.vlesscardvpn.core.AutoChainRunner.fresh(it,Store.state.value.settings,Net.key(this@TunnelService),chainEpoch.get(),android.os.SystemClock.elapsedRealtime())}==true &&
                            Tunnel.status.value.state==Tunnel.State.CONNECTED) connect(soft=true)
                    }}
                    return@launch
                }
                delay(120_000)
            }
        }
    }

    private var torJob: Job? = null
    private var torBridgeRefreshJob: Job? = null
    /**
     * Tor exit rotator, absorbed from tor-rotator-vless (docs/tor-rotator-absorption.md).
     * Opt-in via settings.torRotation. Rotates the exit node of a locally started Tor
     * (embedded libTor.so, or Termux SocksPort/ControlPort — see tor/TorCard) while connected.
     */
    private fun torLoop() {
        if (torJob?.isActive == true) return
        val st = com.vlesscardvpn.tor.TorNetworkRecommendations.apply(Store.state.value.settings, Net.family(Net.key(this)))
        if (!st.torRotation && !st.torRouting && !st.torAll && !(st.mode==Mode.AUTO && st.autoChainSearch && st.torAuto)) return
        torJob = scope.launch(Dispatchers.IO) {
            // Embedded daemon (no Termux needed); Termux instance wins when its cookie is readable.
            try {
                // A first opt-in import may wait at most the catalog call timeout; cached bridges start immediately.
                if (st.torBridge != "none" && com.vlesscardvpn.tor.TorBridgeUpdates.enabled(this@TunnelService) &&
                    runCatching { com.vlesscardvpn.tor.TorBridgeCatalog.selected(st.torBridgeLines, st.torBridge) }.isFailure) {
                    com.vlesscardvpn.tor.TorBridgeUpdates.refresh(this@TunnelService)
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val active = Store.state.value.settings
                if (active.torBridge != st.torBridge || (!active.torRotation && !active.torRouting && !active.torAll && !(active.mode==Mode.AUTO && active.autoChainSearch && active.torAuto))) return@launch
                com.vlesscardvpn.tor.TorCard.ensureStarted(this@TunnelService, active.torBridge,
                    bridgeLines = com.vlesscardvpn.tor.TorBridgeCatalog.selected(active.torBridgeLines, active.torBridge))
                if (torBridgeRefreshJob?.isActive != true) {
                    torBridgeRefreshJob = scope.launch(Dispatchers.IO) {
                        while (true) {
                            val current = Store.state.value.settings
                            if (!current.torRotation && !current.torRouting && !current.torAll && !(current.mode==Mode.AUTO && current.autoChainSearch && current.torAuto)) break
                            com.vlesscardvpn.tor.TorBridgeUpdates.refresh(this@TunnelService)
                            delay(com.vlesscardvpn.tor.TorBridgeRefreshPolicy.RETRY_MS)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("TOR", "Tor startup failed; no direct fallback for Tor routes")
                return@launch
            }
            // A new daemon creates its cookie asynchronously. Do not permanently abandon rotation
            // because the first read raced startup. Delay is cancellable and the wait is bounded.
            val endpoint = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                var found: com.vlesscardvpn.tor.TorCard.ControlEndpoint? = null
                while (found == null) {
                    for (candidate in com.vlesscardvpn.tor.TorCard.controlEndpoints(this@TunnelService)) {
                        val control = com.vlesscardvpn.tor.TorControl("127.0.0.1", candidate.port, 600)
                        try {
                            control.connect(candidate.cookieHex)
                            found = candidate
                            break
                        } catch (_: Exception) {
                        } finally { control.close() }
                    }
                    if (found == null) delay(500)
                }
                found
            }
            if (endpoint == null) { Log.i("TOR", "control endpoint unavailable; rotator idle"); return@launch }
            if (!st.torRotation) return@launch
            val rotator = com.vlesscardvpn.tor.TorRotator(
                com.vlesscardvpn.tor.TorControl("127.0.0.1", endpoint.port),
                rotation = com.vlesscardvpn.tor.TorConnectionPolicy.rotation(st, android.os.SystemClock::elapsedRealtime),
                countries = emptyList(),
                cookieHex = endpoint.cookieHex,
                clearInheritedCountries = true,
            )
            try {
                val ready = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    while (!rotator.connect()) delay(200)
                    true
                } == true
                if (!ready) { Log.i("TOR", "control connection unavailable; rotator idle"); return@launch }
                var lastMeasure = 0L
                while (true) {
                    delay(1_000L)
                    if (Tunnel.status.value.state != Tunnel.State.CONNECTED || !Store.state.value.settings.torRotation) break
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    val online = Net.underlying(this@TunnelService) != null
                    rotator.setOnline(online)
                    if (!online) continue
                    val power = com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService)
                    // Metered/mobile is not an exclusion for this small, user-enabled control loop.
                    if (!power.allowLight) continue
                    // tor-rotator algorithm: measure the real exit through the tunnel (Cloudflare trace, loc=)
                    // and feed the policy — the history learns which countries are actually fast right now.
                    if (nowMs - lastMeasure >= 30_000L) {
                        lastMeasure = nowMs
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.vlesscardvpn.tor.TorProbes.exitInfo(com.vlesscardvpn.tor.TorCard.DEFAULT_SOCKS_PORT)
                        }?.let { (ms, cc) -> rotator.recordExit(ms, cc) }
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if(rotator.tick(android.os.SystemClock.elapsedRealtime())) { chainReserves.clear();chainEpoch.incrementAndGet();chainChoice=null }
                }
            } catch (e: Exception) {
                Log.i("TOR", "rotator stopped: ${e.message}")
            } finally { runCatching { rotator.close() } }
        }
    }

    private var coverJob: Job? = null
    /** «Фон обычного пользователя»: an ordinary Russian site directly every 40 s…3 min while connected (core/Disguise). */
    private fun coverTraffic() {
        if (coverJob?.isActive == true || !Store.state.value.settings.coverTraffic) return
        coverJob = scope.launch {
            while (true) {
                delay(com.vlesscardvpn.core.Disguise.nextDelaySec() * 1000L)
                if (Tunnel.status.value.state != Tunnel.State.CONNECTED || !Store.state.value.settings.coverTraffic) break
                if (com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService).allowed) com.vlesscardvpn.core.Disguise.cover()
            }
        }
    }

    private var serviceJob: Job? = null
    private fun serviceRoutes() {
        if(usedChain != null) return
        if (Store.state.value.settings.torAll || serviceJob?.isActive == true) return
        serviceJob = scope.launch {
            delay(15000)
            var pending = false
            while (Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                val p = com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService)
                if (p.allowLight && !p.deferTraffic && !Actions.progress.value.running) {
                    pending = runCatching { Store.busy { Actions.checkServiceRoutes() } }.getOrDefault(false) || pending
                    if (pending && (!Store.state.value.settings.protectActiveTraffic || !userActive())) {
                        pending = false
                        scope.launch { lock.withLock { connect(soft = true) } }
                        break
                    }
                }
                delay(30000)
            }
        }
    }

    private var optJob: kotlinx.coroutines.Job? = null
    /**
     * Background speed-up while connected. Adaptive: first pass 2 min after a good check; after a switch the next
     * one comes in 5 min (confirm the new choice), and while nothing changes the interval grows 10 → 20 → 30 min,
     * so a stable connection is left alone and the battery is spared. A network change restarts the cycle.
     */
    /**
     * «Прогрев»: right after the tunnel works, the names apps open first (YouTube and its video servers, Google,
     * Telegram, Instagram…) are resolved through it — the DNS cache is warm, the first open doesn't wait for DNS.
     */
    private fun warmUp() {
        val auth = Tunnel.socks ?: return
        scope.launch(Dispatchers.IO) {
            if (!com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService).allowed) return@launch
            WARM.take(com.vlesscardvpn.core.PhonePower.state.value.realParallel).forEach { h -> launch { runCatching { Tester.dnsViaSocks(auth.port, auth.user, auth.pass, h) } } }
        }
    }

    /** A lighter mask/faster route is optional: never hard-restart a healthy VPN for it.
     * Emergency repair keeps its separate immediate reconnect callback. */
    private fun scheduleMaintenanceReconnect() {
        if (maintenanceJob?.isActive == true) return
        val observed = core ?: return
        val net = Net.key(this)
        val handle = Net.underlying(this)?.toString()
        fun current() = core === observed && observed.running && Tunnel.status.value.state == Tunnel.State.CONNECTED &&
            Tunnel.status.value.checkOk == true && Net.key(this) == net && Net.underlying(this)?.toString() == handle
        if (!current()) return
        if (BuildConfig.DEBUG) Log.i("E2E", "maintenance queued")
        maintenanceJob = scope.launch {
            try {
                while (current()) {
                    val remaining = com.vlesscardvpn.core.MaintenanceReconnectPolicy.remaining(
                        android.os.SystemClock.elapsedRealtime(), connectedElapsed)
                    if (remaining > 0) { delay(remaining); continue }
                    val protect = Store.state.value.settings.protectActiveTraffic
                    val uid = android.os.Process.myUid()
                    val rx = android.net.TrafficStats.getUidRxBytes(uid)
                    val tx = android.net.TrafficStats.getUidTxBytes(uid)
                    // Conservative: even a slow transfer (or unavailable counters) defers an optional switch.
                    delay(5000)
                    val idle = com.vlesscardvpn.core.MaintenanceReconnectPolicy.idle(rx, tx,
                        android.net.TrafficStats.getUidRxBytes(uid), android.net.TrafficStats.getUidTxBytes(uid))
                    if (!com.vlesscardvpn.core.MaintenanceReconnectPolicy.mayApply(current(), true, protect, idle)) {
                        if (!current()) return@launch
                        continue
                    }
                    lock.withLock {
                        if (current()) {
                            if (BuildConfig.DEBUG) Log.i("E2E", "maintenance applying=soft")
                            // teardown must not cancel the coroutine performing this reconnect.
                            maintenanceJob = null
                            connect(soft = true)
                        }
                    }
                    return@launch
                }
            } finally { if (maintenanceJob === coroutineContext[Job]) maintenanceJob = null }
        }
    }

    private fun scheduleOptimize(firstDelay: Long = 120_000) {
        if (Store.state.value.settings.torAll) return
        if (optJob?.isActive == true) return
        optJob = scope.launch {
            delay(firstDelay)
            var calm = 0
            while (Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                while (asleep() && Tunnel.status.value.state == Tunnel.State.CONNECTED) delay(60_000) // no re-tests while the phone sleeps
                // Starting test cores/downloads is itself load, even when their resulting reconnect is deferred.
                val startupRemaining = com.vlesscardvpn.core.MaintenanceReconnectPolicy.remaining(
                    android.os.SystemClock.elapsedRealtime(), connectedElapsed)
                if (startupRemaining > 0) { delay(startupRemaining); continue }
                if (!com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService).allowLight) { delay(60_000); continue }
                if (Store.state.value.settings.protectActiveTraffic && userActive() &&
                    !(Store.state.value.settings.mode == Mode.AUTO && usedServers.size < com.vlesscardvpn.core.AutoEligibility.TOP)) { delay(60_000); continue } // hot phone: no background re-tests
                if (chainMeasuring || usedChain != null) { delay(60_000);continue }
                val result = runCatching { Store.busy { Actions.optimize() } }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    false
                }
                // An earlier pass may have deferred applying a changed pool during a call/download.
                // Compare with the running engine, not only with the already-updated Store selection.
                val pending = Store.state.value.let { s -> s.settings.mode == Mode.AUTO &&
                    (s.selected.map { it.id }.toSet() != usedServers.map { it.id }.toSet() ||
                    appliedAutoWeights != com.vlesscardvpn.core.SpeedBalance.fingerprint(
                        if (!com.vlesscardvpn.core.GameSession.active()) usedServers.map {
                            com.vlesscardvpn.core.ServerChoice.measuredSpeed(s.state(it),Net.key(this@TunnelService),System.currentTimeMillis()) } else emptyList(),
                        usedServers.map { s.state(it).realMs })) }
                val reconnect = result || pending
                // the user is in a call / watching video: wait a minute; still busy → skip, the next pass decides again
                val busyNow = reconnect && userActive() && run { Log.i("E2E", "optimize: user is busy — switch postponed"); delay(60_000); userActive() }
                if (reconnect && !busyNow && Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                    Log.i("E2E", "optimize: switching to faster servers")
                    lock.withLock { connect(soft = true) }
                }
                calm = if (Actions.lastOptimizeSwitched) 0 else minOf(calm + 1, 3)
                // sleep until the next pass, or wake early (45 s) when the network changes
                val incomplete = Store.state.value.settings.mode == Mode.AUTO && usedServers.size < com.vlesscardvpn.core.AutoEligibility.TOP
                val next = if (incomplete) 30_000L else longArrayOf(60_000L,120_000L,180_000L,300_000L)[calm]
                if (kotlinx.coroutines.withTimeoutOrNull(next) { optKick.receive() } != null) { calm = 0; delay(5_000) }
            }
        }
    }

    /** Traffic is flowing (call, video, download: > 40 KB/s for 5 s): non-urgent switches wait so they don't cut it. */
    private suspend fun userActive(): Boolean {
        val a = android.net.TrafficStats.getTotalRxBytes() + android.net.TrafficStats.getTotalTxBytes()
        delay(5000)
        val b = android.net.TrafficStats.getTotalRxBytes() + android.net.TrafficStats.getTotalTxBytes()
        return a > 0 && b - a > 200_000
    }

    /** New network (Wi-Fi ↔ mobile): other servers may be faster here — re-run the speed-up soon. */
    private val optKick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private fun optimizeSoon() {
        if (optJob?.isActive == true) optKick.trySend(Unit) else scheduleOptimize(firstDelay = 45_000)
    }

    private var netJob: Job? = null
    private var watchJob: Job? = null
    /**
     * Watchdog: a light request through the tunnel every 20 s (a miss is re-checked after 3 s). Two misses in a row = the connection broke
     * (masking recognised, server died) → self-healing, without waiting for the user to notice.
     */
    private fun watchdog() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            var miss = 0; var dnsMiss = 0; var stall = 0
            var offline = false
            var wasAsleep = false
            var stats = TProxyService.stats()
            while (Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                // wait 45 s (10 s offline), but check right away when the screen turns back on — that's when apps are opened
                var waited = 0L; val period = if (offline) 10_000L else if (miss > 0) 3_000L else com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService).healthyMs
                while (waited < period) { val d = if (asleep()) maxOf(60000L, period - waited) else minOf(5000L, period - waited); delay(d); waited += d; val a = asleep(); if (wasAsleep && !a) { wasAsleep = false; delay(1500); break }; wasAsleep = a }
                com.vlesscardvpn.core.Traffic.tick()
                if (healJob?.isActive == true || asleep()) continue
                val auth = Tunnel.socks ?: break
                val port = auth.port
                val vpnMode = true
                // the TUN engine stopped: nothing in the phone works, though the proxy itself is fine — rebuild like the big button
                if (vpnMode && runCatching { !TProxyService.TProxyIsRunning() }.getOrDefault(false)) { hardRestart("движок VPN остановился"); break }
                val ok = Tester.probeSocks(port, Store.state.value.settings.testUrl, big = false, youtube = false, attempts = 1).realMs > 0
                if (!ok && !Actions.online()) {
                    miss = 0
                    val cur = Tunnel.status.value
                    if (cur.state == Tunnel.State.CONNECTED) Tunnel.status.value = cur.copy(check = "Нет интернета у самой сети — VPN ждёт и восстановится сам", checkOk = null)
                    offline = true
                    continue
                }
                if (ok && offline) { verifySoon(0); break } // internet is back: full check (WARP, YouTube…)
                miss = if (ok) 0 else miss + 1
                if (miss >= 2) {
                    val callActive = com.vlesscardvpn.core.PhonePower.isVoiceCallActive(this@TunnelService)
                    if (com.vlesscardvpn.core.VoiceCallPolicy.deferRepair(callActive, miss)) {
                        val cur = Tunnel.status.value
                        Tunnel.status.value = cur.copy(check = "Идёт звонок — перепроверяю маршрут без перезапуска", checkOk = false)
                        continue
                    }
                    Log.i("E2E", "watchdog: connection lost")
                    val cur = Tunnel.status.value
                    Tunnel.status.value = cur.copy(check = "Связь пропала — пересобираю соединение…", checkOk = false)
                    hardRestart("сервер перестал отвечать"); break
                }
                if (ok && vpnMode) {
                    // the proxy works, but do apps? DNS the way apps ask it, and traffic through the TUN in both directions
                    dnsMiss = if (Tester.dnsViaSocks(port, auth.user, auth.pass) || Tester.dnsViaSocks(port, auth.user, auth.pass, "cloudflare.com")) 0 else dnsMiss + 1
                    val now = TProxyService.stats()
                    stall = if (Tester.tunStalled(stats, now)) stall + 1 else 0
                    stats = now
                    val callActive = com.vlesscardvpn.core.PhonePower.isVoiceCallActive(this@TunnelService)
                    if ((dnsMiss >= 2 || stall >= 3) && com.vlesscardvpn.core.VoiceCallPolicy.deferRepair(callActive, maxOf(dnsMiss, stall))) {
                        val cur = Tunnel.status.value
                        Tunnel.status.value = cur.copy(check = "Идёт звонок — подтверждаю сбой перед перезапуском", checkOk = false)
                        continue
                    }
                    if (dnsMiss >= 2) { hardRestart("DNS перестал отвечать"); break }
                    if (stall >= 3) { hardRestart("трафик приложений завис"); break }
                }
            }
        }
    }

    private var scoutJob: Job? = null
    private fun standbyScout() {
        if(usedChain != null) return
        if (Store.state.value.settings.torAll) return
        scoutJob?.cancel()
        scoutJob = scope.launch {
            var previous = TProxyService.stats(); var windows = 0; var lastAttempt = 0L
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                if (!com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService).screenInteractive) {
                    com.vlesscardvpn.core.PhonePower.state.first { it.screenInteractive }
                    previous = TProxyService.stats(); windows = 0
                }
                if (!com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService).allowLight) {
                    windows = 0; previous = null; delay(60000); continue
                }
                delay(5000)
                val current = TProxyService.stats()
                val a=previous;previous=current
                if (asleep() || Store.state.value.settings.mode != com.vlesscardvpn.model.Mode.AUTO ||
                    !com.vlesscardvpn.core.PhonePower.refresh(this@TunnelService).allowLight || healJob?.isActive == true) { windows=0;continue }
                val suspicious = a != null && current != null && a.size>=4 && current.size>=4 && current[1]-a[1]>=4096 && current[3]==a[3]
                windows=if (suspicious) windows+1 else 0
                val now=System.currentTimeMillis()
                if (windows<2 || now-lastAttempt<120000) continue
                windows=0;lastAttempt=now
                val original=Tunnel.socks ?: continue;val network=Net.key(this@TunnelService)
                val healthy=Tester.probeSocks(original.port,Store.state.value.settings.testUrl,big=false,youtube=false,attempts=1,budgetMs=1200).works
                if (healthy) continue
                val spare=runCatching { Actions.prepareStandby(usedServers) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else null } ?: continue
                if (Tunnel.socks != original || Net.key(this@TunnelService)!=network || asleep()) continue
                Actions.selectBest(1,among=listOf(spare),close=true)
                scope.launch { lock.withLock { connect(soft=true) } }
                break
            }
        }
    }

    /** What the big button does (stop + start), by itself: TUN, DNS and Xray are rebuilt. */
    private var lastHard = 0L
    @Volatile private var lastSoft = false
    @Volatile private var netChangedAt = 0L
    @Volatile private var netHardDone = true
    /** The network the tunnel was built on; kept across reconnects so a change during a reconnect is not missed. */
    @Volatile private var curNet: Network? = null
    private fun hardRestart(why: String, force: Boolean = false) {
        // A restart can be caused by DNS/TUN/app stalls; it is not a failed probe of every server.
        val now = System.currentTimeMillis()
        if (!force && now - lastHard < 5 * 60_000L) { heal(); return } // twice in 5 min: restarting doesn't help — heal (masks, servers)
        lastHard = now
        Log.i("E2E", "watchdog: hard restart — $why")
        val cur = Tunnel.status.value
        if (cur.state == Tunnel.State.CONNECTED) Tunnel.status.value = cur.copy(check = "Сам переподключаюсь: $why", checkOk = null)
        com.vlesscardvpn.core.AppWatch.note("Связь: $why — переподключился сам, нажимать кнопку не нужно")
        scope.launch { lock.withLock { connect(soft = false) } }
    }

    private var healJob: Job? = null
    private var recoveryJob: Job? = null
    private fun healStatus(text: String) { val cur = Tunnel.status.value; if (cur.state == Tunnel.State.CONNECTED) Tunnel.status.value = cur.copy(check = text, checkOk = null) }

    private fun recoverServerRoute() {
        if (recoveryJob?.isActive == true || tun == null || Tunnel.socks == null) return
        recoveryJob = scope.launch {
            var attempt = 0
            while (tun != null && Tunnel.socks != null && Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                if (com.vlesscardvpn.core.PhonePower.isVoiceCallActive(this@TunnelService)) {
                    healStatus("Идёт звонок — не переключаю маршрут; VPN блокирует трафик до восстановления сервера")
                    delay(10_000)
                    continue
                }
                if (!Actions.online()) {
                    healStatus("Сеть недоступна — VPN оставляет трафик заблокированным и повторит поиск")
                    delay(20_000)
                    continue
                }
                healStatus(if (attempt == 0) "Сервер не отвечает — ищу рабочий VLESS-маршрут; прямого обхода нет" else "VLESS-маршрут не найден — повторяю поиск")
                val level = if (attempt == 0) 1 else 3
                val found = runCatching { Store.busy { Actions.autoRescue(usedServers, level) } }.getOrDefault(0)
                if (found > 0) {
                    safeMasks = false
                    connectFails = 0
                    lock.withLock {
                        if (tun != null && Tunnel.status.value.state == Tunnel.State.CONNECTED) connect(soft = true)
                    }
                    if (Tunnel.status.value.check == "Проверяю интернет…") break
                }
                attempt++
                healStatus("Рабочего VLESS-сервера пока нет. Трафик остаётся заблокированным; повторный поиск через ${minOf(60, attempt * 5)} с")
                delay(minOf(60_000L, 5_000L * attempt.coerceAtMost(12)))
            }
        }
    }

    private fun heal() {
        val st = Store.state.value
        if (healJob?.isActive == true) return
        if (st.settings.mode == Mode.AUTO) {
            recoverServerRoute()
            return
        }
        if (!st.settings.autoHeal) return
        val servers = usedServers.filter(com.vlesscardvpn.core.AutoEligibility::vlessServer)
        if (servers.isEmpty()) return
        healJob = scope.launch {
            if (!Actions.online()) {
                healStatus("Нет интернета у самой сети — маршрут через VLESS оставлен без прямого обхода")
                delay(20_000)
                verifySoon(0)
                return@launch
            }
            val reachable = healStep == 0 && servers.take(5).any { Tester.tcpOne(it.address, it.port, 1500) > 0 }
            healStep++
            if (reachable && healStep == 1 && Actions.nextMasks(servers)) {
                healStatus("Проверяю следующую маскировку VLESS-сервера…")
            } else {
                healStatus("Ищу рабочую маскировку для выбранного VLESS-сервера…")
                runCatching { Store.busy { Actions.doFindMasks(servers, 24) } }
            }
            val stillWorking = Store.state.value.servers.filter { server -> server.id in servers.map { it.id }.toSet() && Store.state.value.state(server).works }
            if (stillWorking.isEmpty()) {
                healStatus("Выбранный сервер не восстановлен. Трафик не отправляется напрямую; выберите другой сервер или Авто.")
                return@launch
            }
            delay(500)
            lock.withLock { connect(soft = true) }
        }
    }

    /** Observe physical networks, not only the VPN default. Wi-Fi→Wi-Fi can also change link properties. */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val initial = Net.underlying(this)
        curNet = initial
        handoff.seed(initial?.let { NetworkHandoff.Snapshot(it.toString(), Net.key(this, it)) })
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = queueNetworkRefresh()
            override fun onLost(network: Network) = queueNetworkRefresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = queueNetworkRefresh()
            override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) = queueNetworkRefresh()
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        runCatching { cm.registerNetworkCallback(request, cb); netCallback = cb }
            .onFailure { Log.w(TAG, "physical network observer registration failed", it) }
    }

    private fun queueNetworkRefresh() {
        val signal = networkSignal.incrementAndGet()
        networkRefreshJob?.cancel()
        networkRefreshJob = scope.launch {
            delay(500) // wait for DHCP/capabilities, coalesce a burst of Android callbacks
            if (networkSignal.get() != signal || netCallback == null) return@launch
            val network = Net.underlying(this@TunnelService)
            val profile = network?.let { Net.key(this@TunnelService, it) }
            val snapshot = network?.let { NetworkHandoff.Snapshot(it.toString(), profile!!) }
            val change = handoff.update(snapshot) ?: return@launch
            // Carry endpoints only as unverified candidate hints, never their old-network samples.
            val previousCandidates=(usedServers+chainReserves.candidateServers()).distinctBy {it.id}.take(8)
            cancelChainSearch()
            curNet = network
            runCatching { setUnderlyingNetworks(network?.let { arrayOf(it) }) }
            netJob?.cancel(); checkJob?.cancel(); healJob?.cancel(); recoveryJob?.cancel(); optJob?.cancel(); scoutJob?.cancel()
            watchJob?.cancel(); coverJob?.cancel(); serviceJob?.cancel(); liveJob?.cancel(); torJob?.cancel(); torJob = null
            torBridgeRefreshJob?.cancel(); torBridgeRefreshJob = null
            com.vlesscardvpn.tor.TorCard.invalidateStartup()
            Actions.cancelForNetworkChange()
            if (!change.online) {
                val current = Tunnel.status.value
                if (current.state == Tunnel.State.CONNECTED) Tunnel.status.value = current.copy(
                    check = "Сеть пропала — жду новую, её отсутствие не портит обучение серверов", checkOk = null)
                return@launch
            }
            netChangedAt = System.currentTimeMillis(); netHardDone = false
            netJob = scope.launch reconnect@ {
                delay(700)
                if (!handoff.valid(change.revision)) return@reconnect
                val state = Tunnel.status.value.state
                if (state != Tunnel.State.CONNECTED && state != Tunnel.State.CONNECTING) return@reconnect
                healStep = 0; dpiHeals = 0; connectFails = 0; safeMasks = false
                healStatus("Новая сеть — подстраиваю сервер, маскировку и локальный профиль…")
                if (com.vlesscardvpn.tor.TorConnectionPolicy.needsAutoSearch(Store.state.value.settings)) {
                    Actions.recallNet(profile!!)
                    runCatching { Actions.quickRecheck(previousCandidates) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                }
                if (!handoff.valid(change.revision)) return@reconnect
                lock.withLock {
                    if (handoff.valid(change.revision)) {
                        Log.i("E2E", "physical network changed → applying new profile")
                        connect(soft = true)
                    }
                }
            }
        }
    }

    private fun teardown(error: String?, keepTun: Boolean = false) {
        maintenanceJob?.cancel(); maintenanceJob = null
        if (BuildConfig.DEBUG) Log.i("E2E", "teardown state=${Tunnel.status.value.state} keepTun=$keepTun reason=${if(error == null) "restart" else if(error.isEmpty()) "stop" else "error"}")
        chainJob?.cancel();chainJob=null;usedChain=null
        if(error != null || !keepTun) { chainReserves.clear();chainChoice=null;chainEpoch.incrementAndGet() }
        torJob?.cancel(); torJob = null
        torBridgeRefreshJob?.cancel(); torBridgeRefreshJob = null
        com.vlesscardvpn.tor.TorCard.invalidateStartup()
        if (error != null) com.vlesscardvpn.tor.TorCard.stopEmbedded()
        if (!keepTun) runCatching { com.vlesscardvpn.core.Traffic.tick() }
        liveJob?.cancel()
        scoutJob?.cancel(); scoutJob=null
        checkJob?.cancel(); watchJob?.cancel(); coverJob?.cancel(); serviceJob?.cancel(); if (error != null) netJob?.cancel()
        if (error != null) { healJob?.cancel(); recoveryJob?.cancel() }
        if (error != null) optJob?.cancel()
        if (error != null) {
            netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
            netCallback = null; curNet = null
            networkRefreshJob?.cancel(); networkSignal.incrementAndGet(); handoff.reset()
        }
        if (!keepTun) TProxyService.stop()
        core?.stop(); core = null; Tunnel.wireguardKeys = emptySet()
        dpi?.close(); dpi = null; Tunnel.byeDpiPort = null
        if (!keepTun) { Tunnel.socks = null; runCatching { tun?.close() }; tun = null }
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

    private var liveJob: Job? = null

    /** Speed right in the notification (every 10 s, only with the screen on — nothing runs while the phone sleeps). */
    private fun liveNotification(route: String) {
        liveJob?.cancel()
        if (Store.state.value.settings.quietNotification) return
        liveJob = scope.launch {
            val uid = android.os.Process.myUid()
            var rx = android.net.TrafficStats.getUidRxBytes(uid); var tx = android.net.TrafficStats.getUidTxBytes(uid); var t = System.nanoTime()
            while (Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                delay(10_000)
                if (!(getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: true)) {
                    com.vlesscardvpn.core.PhonePower.state.first { it.screenInteractive }
                    rx = android.net.TrafficStats.getUidRxBytes(uid); tx = android.net.TrafficStats.getUidTxBytes(uid); t = System.nanoTime(); continue
                }
                if (Tunnel.pausedUntil.value > 0) continue
                val r2 = android.net.TrafficStats.getUidRxBytes(uid); val t2x = android.net.TrafficStats.getUidTxBytes(uid); val now = System.nanoTime()
                val ms = ((now - t) / 1_000_000).coerceAtLeast(1)
                val down = ((r2 - rx) * 8 / ms).toInt().coerceAtLeast(0); val up = ((t2x - tx) * 8 / ms).toInt().coerceAtLeast(0)
                rx = r2; tx = t2x; t = now
                runCatching { foreground("↓ ${Actions.mbps(down)} ↑ ${Actions.mbps(up)} · $route") }
            }
        }
    }

    private fun foreground(text: String, paused: Boolean = false) {
        val quiet = Store.state.value.settings.quietNotification
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(if (quiet) android.R.drawable.stat_notify_sync_noanim else android.R.drawable.ic_lock_lock)
            .setContentTitle(if (quiet) "Синхронизация" else "VLESS Card").setContentText(if (quiet) "Активно" else text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .apply {
                if (paused) addAction(Notification.Action.Builder(null, "Включить сейчас",
                    PendingIntent.getService(this@TunnelService, 3, Intent(this@TunnelService, TunnelService::class.java).setAction(ACTION_START), PendingIntent.FLAG_IMMUTABLE)).build())
                else addAction(Notification.Action.Builder(null, "Пауза 5 мин",
                    PendingIntent.getService(this@TunnelService, 2, Intent(this@TunnelService, TunnelService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE)).build())
            }
            .addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }
}
