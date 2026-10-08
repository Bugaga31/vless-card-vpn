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
import com.vlesscardvpn.model.Settings
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
        const val ACTION_PAUSE = "com.vlesscardvpn.PAUSE"
        const val PAUSE_MS = 5 * 60_000L
        /** «Ускорение»: a big TUN MTU — 5-6 times fewer packets for the phone to move for the same traffic (less CPU, less battery). */
        const val TURBO_MTU = 8500
        private const val CHANNEL = "vpn"
        private const val TAG = "TunnelService"
        const val MTU = 1500
        const val IPV4 = "10.10.14.1"
        const val IPV6 = "fd66:6c65:7373::1"

        fun start(context: Context) {
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
    /** Self-healing step after failed checks (0 = healthy); reset by a successful check or a user (re)connect. */
    private var healStep = 0
    /** Auto mode gave up on servers for this connection: ByeDPI only (the search goes on in the background). */
    private var autoDpiOnly = false
    /** Servers of the running connection (selected or the best ones taken automatically). */
    @Volatile private var usedServers: List<com.vlesscardvpn.model.Server> = emptyList()
    /** DPI-strategy repairs in a row (reset by a good check). */
    private var dpiHeals = 0
    /** Connect attempts that failed to start (Auto mode retries with safer settings). */
    private var connectFails = 0
    /** Retry after a failed start: links' own settings, no masks. */
    private var safeMasks = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_PAUSE) { pauseJob?.cancel(); Tunnel.pausedUntil.value = 0 }
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { lock.withLock { pause() } }
            ACTION_STOP -> { scope.launch { lock.withLock { teardown("") } ; stopSelf() } }
            else -> { // ACTION_START or always-on restart (null intent)
                foreground("Подключение…")
                healStep = 0; autoDpiOnly = false; dpiHeals = 0; connectFails = 0; safeMasks = false
                scope.launch { lock.withLock { connect() } }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() { scope.launch { lock.withLock { teardown("VPN отключён системой или другим VPN") }; stopSelf() } }

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
        if (!Store.state.value.settings.turbo) return false
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return false
        return !pm.isInteractive || pm.isPowerSaveMode
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
                    if (!ok) verifySoon(0) else { watchdog(); coverTraffic() }
                }
            }
        }
        runCatching { registerReceiver(r, android.content.IntentFilter(Intent.ACTION_USER_PRESENT)); screenReceiver = r }
    }

    override fun onDestroy() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }; screenReceiver = null
        if (Tunnel.status.value.state == Tunnel.State.CONNECTED) runCatching { com.vlesscardvpn.core.Traffic.tick() }
        // Never block the main thread on a long connect/heal holding the lock (that was an ANR): wait 1.5 s at most.
        runCatching { kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(1500) { lock.withLock { teardown(null) } } ?: teardown(null) } }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * [soft]: internal reconnect (network change, healing, speed-up) — the TUN and its local proxy stay, only Xray and
     * the DPI engines restart. No moment without VPN: apps don't leak past it and don't drop everything on "VPN off".
     */
    private suspend fun connect(soft: Boolean = false) {
        val prevSocks = Tunnel.socks
        val keepTun = soft && tun != null && prevSocks != null && !Store.state.value.settings.proxyOnly
        teardown(null, keepTun)
        Store.init(this); XrayCore.init(this); Actions.init(this)
        val prev = Tunnel.status.value
        Tunnel.status.value = if (keepTun && prev.state == Tunnel.State.CONNECTED) prev.copy(check = "Переподключаюсь без разрыва VPN…", checkOk = null)
            else Tunnel.Status(Tunnel.State.CONNECTING, "Подключение…")
        try {
            val st = Store.state.value
            val network = Net.key(this)
            var settings = st.settings
            var selected = st.selected
            if (settings.mode != Mode.BYEDPI && selected.isEmpty()) {
                // Nothing chosen: take the best servers that passed the last test.
                selected = Actions.preferred(st, st.servers.filter { st.state(it).works }).sortedBy { st.state(it).score }.take(5)
                    .let { l -> l.take(Actions.closeOnes(l.map { st.state(it).score })) }
            }
            if (settings.mode == Mode.AUTO) {
                settings = settings.copy(mode = if (selected.isEmpty() || autoDpiOnly) Mode.BYEDPI else Mode.SERVERS)
                if (settings.mode == Mode.BYEDPI) selected = emptyList()
            }
            if (settings.mode != Mode.BYEDPI && selected.isEmpty())
                error("Нет выбранных серверов. Откройте «Серверы», нажмите «Проверить» и отметьте рабочие.")
            selected = Actions.pinCertificates(selected)
            usedServers = selected
            val rotate = settings.rotateMasks
            val withMasks = selected.map { srv ->
                val ss = st.state(srv)
                val mine = ss.maskFor(network)
                val id = if (rotate && ss.goodMasks.isNotEmpty()) (ss.goodMasks + mine).filter { it.isNotEmpty() }.random() else mine
                srv to if (safeMasks) null else Masks.byId(id)
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
            val socks = if (keepTun && prevSocks != null) prevSocks else if (settings.stealthSocks && !proxy) SocksAuth(randomPort(), randomToken(), randomToken()) else SocksAuth(settings.socksPort)
            val config = XrayConfigBuilder.vpnConfig(withMasks, settings, bdPort, socks, set.ports,
                listen = if (proxy && settings.lanShare) "0.0.0.0" else "127.0.0.1", httpPort = if (proxy) settings.httpPort else null)
            val c = XrayCore.Instance("vpn"); core = c
            c.start(config)
            check(c.running) { "Xray не запустился: ${c.lastStatus}" }
            Tunnel.socks = socks
            Log.i("E2E", "socks port=${socks.port} auth=${socks.auth} dpi=${dpiLabel.ifEmpty { "-" }} fixed=${set.ports.keys} mode=${settings.mode} services=${settings.services} proxy=$proxy")
            if (!proxy && !keepTun) startTun(settings, socks)

            val auto = st.settings.mode == Mode.AUTO
            var route = when (settings.mode) {
                Mode.BYEDPI -> "Без сервера · $dpiLabel"
                Mode.HYBRID -> "${selected.size} серв. + $dpiLabel для YouTube/Discord"
                else -> if (selected.size == 1) selected[0].name else "${selected.size} серверов · ${settings.balance.title.lowercase()}"
            }
            if (auto) route = "Авто · $route"
            if (settings.services.isNotEmpty()) route += " · через VPN только " + com.vlesscardvpn.core.Services.label(settings.services)
            if (proxy) route = "Прокси SOCKS5 :${socks.port} · HTTP :${settings.httpPort}" + (if (settings.lanShare) " (для всей сети: ${lanIp() ?: "IP телефона"})" else "") + " · $route"
            val since = if (keepTun && prev.since > 0) prev.since else System.currentTimeMillis()
            if (!keepTun) { com.vlesscardvpn.core.Traffic.start(); Tunnel.rx0 = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid()); Tunnel.tx0 = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid()) }
            Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTED, "Подключено", route, "Проверяю интернет…", null, since)
            watchScreen()
            foreground(if (settings.quietNotification) "Активно" else "Подключено · $route")
            liveNotification(route)
            watchNetwork()
            verifySoon(0)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "connect failed", e)
            Log.i("E2E", "connect failed: ${e.message}")
            // Auto mode doesn't give up on a failed start: 1) without masks, 2) DPI bypass without servers.
            if (Store.state.value.settings.mode == Mode.AUTO && connectFails < 2) {
                connectFails++
                if (connectFails == 1) safeMasks = true else autoDpiOnly = true
                Log.i("E2E", "connect retry #$connectFails safe=$safeMasks dpiOnly=$autoDpiOnly")
                teardown(null)
                Tunnel.status.value = Tunnel.Status(Tunnel.State.CONNECTING, "Подключение…", check = "Не запустилось (${e.message?.take(80)}) — пробую по-другому…")
                connect(); return
            }
            teardown(e.message ?: e.javaClass.simpleName)
            stopSelf()
        }
    }

    private fun startTun(settings: com.vlesscardvpn.model.Settings, socks: SocksAuth) {
        val v4 = if (settings.randomTun) com.vlesscardvpn.core.Disguise.tunV4() else IPV4
        val v6 = if (settings.randomTun) com.vlesscardvpn.core.Disguise.tunV6() else IPV6
        val mtu = if (settings.turbo) TURBO_MTU else MTU
        val b = Builder().setSession(if (settings.randomTun || settings.quietNotification) "Sync" else "VLESS Card").setMtu(mtu)
            .addAddress(v4, 30).addRoute("0.0.0.0", 0)
            .addAddress(v6, 126).addRoute("::", 0)
            .addDnsServer("1.1.1.1").addDnsServer("8.8.8.8")
            .setConfigureIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        val apps = settings.apps.filter { it != packageName }
        if (!settings.perApp) b.addDisallowedApplication(packageName)
        else if (settings.onlyApps && apps.isNotEmpty()) apps.forEach { runCatching { b.addAllowedApplication(it) } }
        else {
            b.addDisallowedApplication(packageName); apps.forEach { runCatching { b.addDisallowedApplication(it) } }
            // banks, Госуслуги, маркетплейсы: around the VPN — they see a normal Russian user (skipped if not installed)
            if (settings.ruAppsDirect) (com.vlesscardvpn.core.Disguise.RU_APPS - apps.toSet()).forEach { runCatching { b.addDisallowedApplication(it) } }
        }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        val fd = b.establish() ?: error("Система не дала создать VPN (нет разрешения)")
        tun = fd
        TProxyService.start(filesDir, fd, socks.port, mtu, v4, v6, socks.user, socks.pass)
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
            // WARP: ask Cloudflare itself whether the traffic comes through WARP (warp=on / plus), so it's clear it works.
            val warp = if (p.realMs > 0 && usedServers.any { Masks.isWarp(it) }) when (Tester.warpTrace(port)) {
                "plus" -> " · WARP+: включён ✓"; "on" -> " · WARP: включён ✓"; "off" -> " · WARP: не через Cloudflare ✗"; else -> " · WARP: Cloudflare не ответил"
            } else ""
            val text = if (p.realMs > 0) "Интернет работает · ${p.realMs} мс · 256 КБ: ${Actions.yn(p.bigOk)} · YouTube: ${Actions.yn(p.ytOk)} · Telegram: ${Actions.yn(p.tgOk)}$warp"
                else "Нет ответа через выбранный маршрут (${p.error}). " + if (st.mode == Mode.AUTO && st.autoHeal) "Ищу рабочий вариант сам…" else "Проверьте серверы или включите маскировку."
            Tunnel.status.value = cur.copy(check = text, checkOk = p.works)
            Log.i("E2E", "check ok=${p.works} ms=${p.realMs} big=${p.bigOk} yt=${p.ytOk} tg=${p.tgOk} err=${p.error} warp=$warp")
            if (p.works && st.mode == Mode.AUTO) Actions.rememberNet(Net.key(this@TunnelService))
            if (p.works) { healStep = 0; dpiHeals = 0; connectFails = 0; scheduleOptimize(if (com.vlesscardvpn.core.Actions.deepPending) 40_000 else 120_000); watchdog(); coverTraffic(); if (serverless()) rescueLater() } else heal()
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
                if (!asleep()) com.vlesscardvpn.core.Disguise.cover()
            }
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
                while (asleep() && Tunnel.status.value.state == Tunnel.State.CONNECTED) delay(60_000) // no re-tests while the phone sleeps
                val reconnect = runCatching { Store.busy { Actions.optimize() } }.getOrDefault(false)
                // the user is in a call / watching video: wait a minute; still busy → skip, the next pass decides again
                val busyNow = reconnect && userActive() && run { Log.i("E2E", "optimize: user is busy — switch postponed"); delay(60_000); userActive() }
                if (reconnect && !busyNow && Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                    Log.i("E2E", "optimize: switching to faster servers")
                    lock.withLock { connect(soft = true) }
                }
                calm = if (Actions.lastOptimizeSwitched) 0 else minOf(calm + 1, 3)
                // sleep until the next pass, or wake early (45 s) when the network changes
                if (kotlinx.coroutines.withTimeoutOrNull(longArrayOf(5, 10, 20, 30)[calm] * 60_000L) { optKick.receive() } != null) { calm = 0; delay(45_000) }
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
     * Watchdog: a light request through the tunnel every 45 s. Two misses in a row = the connection broke
     * (masking recognised, server died) → self-healing, without waiting for the user to notice.
     */
    private fun watchdog() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            var miss = 0
            var offline = false
            while (Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                delay(if (offline) 10_000 else 45_000)
                com.vlesscardvpn.core.Traffic.tick()
                if (healJob?.isActive == true || asleep()) continue
                val port = Tunnel.socks?.port ?: break
                val ok = Tester.probeSocks(port, Store.state.value.settings.testUrl, big = false, youtube = false, attempts = 1).realMs > 0
                if (!ok && !Actions.online()) {
                    miss = 0
                    val cur = Tunnel.status.value
                    if (cur.state == Tunnel.State.CONNECTED) Tunnel.status.value = cur.copy(check = "Нет интернета у самой сети — VPN ждёт и восстановится сам", checkOk = null)
                    offline = true
                    continue
                }
                if (ok && offline) { offline = false; verifySoon(0); break } // internet is back: full check (WARP, YouTube…)
                miss = if (ok) 0 else miss + 1
                if (miss >= 2) {
                    Log.i("E2E", "watchdog: connection lost")
                    val cur = Tunnel.status.value
                    Tunnel.status.value = cur.copy(check = "Связь пропала — ищу рабочий вариант…", checkOk = false)
                    heal(); break
                }
            }
        }
    }

    private var healJob: Job? = null
    private fun healStatus(text: String) { val cur = Tunnel.status.value; if (cur.state == Tunnel.State.CONNECTED) Tunnel.status.value = cur.copy(check = text, checkOk = null) }

    /**
     * Masking got blocked (or the server died): 1) next good masks, 2) mask search for the selected servers.
     * Auto mode never gives up: other working servers → mask search with mask evolution → fresh subscriptions,
     * full re-test and WARP; only then ByeDPI without servers, and the search goes on in the background.
     */
    private fun heal() {
        val st = Store.state.value
        if (!st.settings.autoHeal || healJob?.isActive == true) return
        val auto = st.settings.mode == Mode.AUTO
        if (st.settings.mode == Mode.BYEDPI || autoDpiOnly) {
            // DPI bypass without a server stopped working: the next strategy remembered for this network, else a new search
            if (st.settings.dpiStrategy != Settings.DPI_AUTO || dpiHeals >= 6) {
                healStatus(if (dpiHeals >= 6) "Обход DPI в этой сети не помогает — нужен сервер (режим «Авто» найдёт его сам)" else "Стратегия выбрана вручную — включите «Авто» в Настройках → Обход DPI")
                return
            }
            dpiHeals++
            healJob = scope.launch {
                if (!Actions.online()) { healStatus("Нет интернета у самой сети — жду, когда появится…"); dpiHeals--; delay(20_000); verifySoon(0); return@launch }
                if (dpiHeals % 3 != 0 && Actions.nextDpi()) healStatus("Стратегию, похоже, распознали — переключаюсь на запасную…")
                else { healStatus("Подбираю новую стратегию обхода для этой сети…"); runCatching { Store.busy { Actions.doFindDpi() } } }
                Log.i("E2E", "heal dpi=$dpiHeals")
                delay(500); lock.withLock { connect(soft = true) }
            }
            return
        }
        val servers = usedServers
        healStep++
        healJob = scope.launch {
            if (!Actions.online()) {
                // no internet at all (lift, metro): nothing to fix, check again a bit later
                healStatus("Нет интернета у самой сети — жду, когда появится…")
                healStep--; delay(20_000); verifySoon(0); return@launch
            }
            if (healStep == 1 && Actions.nextMasks(servers)) {
                healStatus("Маскировку, похоже, распознали — переключаюсь на другую…")
            } else if (!auto) {
                if (healStep > 2) return@launch
                healStep = 2
                healStatus("Подбираю новую маскировку для серверов…")
                runCatching { Store.busy { Actions.doFindMasks(servers, 24) } }
            } else if (healStep <= 4) {
                var n = 0
                for (level in 1..3) {
                    healStatus("Авто-поиск, шаг $level из 3: " + when (level) { 1 -> "переключаюсь на другие рабочие серверы"; 2 -> "подбираю маскировку"; else -> "перепроверяю всё" } + "…")
                    n = runCatching { Store.busy { Actions.autoRescue(servers, level) { step -> healStatus("Авто-поиск, шаг $level из 3: $step…") } } }.getOrDefault(0)
                    if (n > 0) break
                }
                if (n == 0) { autoDpiOnly = true; healStatus("Серверы пока не нашлись — включаю обход DPI без сервера и ищу дальше в фоне…") }
            } else {
                autoDpiOnly = true
                healStatus("Серверы раз за разом отваливаются — пока обход DPI без сервера, поиск идёт в фоне…")
            }
            Log.i("E2E", "heal step=$healStep dpiOnly=$autoDpiOnly")
            safeMasks = false; connectFails = 0
            delay(500)
            lock.withLock { connect(soft = true) }
        }
    }

    private var rescueJob: Job? = null
    /** Auto mode running without servers (none worked, or none known yet). */
    private fun serverless() = Store.state.value.settings.mode == Mode.AUTO && (autoDpiOnly || usedServers.isEmpty())
    /** Auto mode on ByeDPI only: every 3 min look for servers again; as soon as some work, switch back to them. */
    private fun rescueLater() {
        if (rescueJob?.isActive == true) return
        rescueJob = scope.launch {
            while (serverless() && Tunnel.status.value.state == Tunnel.State.CONNECTED) {
                delay(180_000)
                if (healJob?.isActive == true) continue
                val n = runCatching { Store.busy { Actions.autoRescue(emptyList(), 3) } }.getOrDefault(0)
                if (n > 0 && serverless()) {
                    Log.i("E2E", "rescue: servers found again")
                    autoDpiOnly = false; healStep = 0
                    lock.withLock { connect(soft = true) }
                    break
                }
            }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        var last: Network? = null
        var lost = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return
                val changed = last != null && (last != network || lost)
                last = network; lost = false
                setUnderlyingNetworks(arrayOf(network))
                if (!changed) return
                // Another network (Wi-Fi ↔ mobile) or the same one back: connections to the server died with the old
                // network (apps used to hang for minutes). Restart Xray at once — its DPI strategy and masks for this network too.
                netJob?.cancel()
                netJob = scope.launch {
                    delay(1500)
                    Log.i("E2E", "network changed → soft restart")
                    if (Store.state.value.settings.mode == Mode.AUTO && Actions.recallNet(Net.key(this@TunnelService))) Log.i("E2E", "network changed → remembered servers")
                    lock.withLock { connect(soft = true) }
                }
                if (Store.state.value.settings.autoOptimize) optimizeSoon()
            }

            override fun onLost(network: Network) {
                if (network != last) return
                lost = true
                netJob?.cancel()
                val cur = Tunnel.status.value
                if (cur.state == Tunnel.State.CONNECTED) Tunnel.status.value = cur.copy(check = "Сеть пропала — VPN ждёт её и восстановится сам", checkOk = null)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    private fun teardown(error: String?, keepTun: Boolean = false) {
        if (!keepTun) runCatching { com.vlesscardvpn.core.Traffic.tick() }
        liveJob?.cancel()
        checkJob?.cancel(); watchJob?.cancel(); coverJob?.cancel(); if (error != null) netJob?.cancel()
        if (error != null) { healJob?.cancel(); rescueJob?.cancel() }
        if (error != null) optJob?.cancel()
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        if (!keepTun) TProxyService.stop()
        core?.stop(); core = null
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
                if (asleep() || Tunnel.pausedUntil.value > 0) { rx = android.net.TrafficStats.getUidRxBytes(uid); tx = android.net.TrafficStats.getUidTxBytes(uid); t = System.nanoTime(); continue }
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
