package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.LinkParser

/**
 * Tor exposes a loopback SOCKS port; the embedded daemon sends its relay traffic
 * through the dedicated Xray VLESS-upstream listener. An unmanaged Tor instance is
 * accepted only when its control port confirms that same upstream.
 */
object TorCard {
    const val CARD_NAME = "Tor · ротатор"
    const val DEFAULT_SOCKS_PORT = 9050
    const val DEFAULT_CONTROL_PORT = 9091
    const val DEFAULT_UPSTREAM_PORT = 19052
    const val DEFAULT_PT_UPSTREAM_PORT = 19053

    /** Termux data dir where torrc + cookie live. */
    const val TERMUX_TORRC_PATH = "/data/data/com.termux/files/usr/etc/tor/torrc"

    /** Termux data dir holding the ControlPort authentication cookie. */
    const val TERMUX_COOKIE_PATH = "/data/data/com.termux/files/usr/var/lib/tor/control_auth_cookie"

    /** Control cookie: Termux instance first, then the embedded daemon's own cookie. */
    fun readCookieHex(ctx: android.content.Context?): String? {
        runCatching { TorControl.readCookieHex(TERMUX_COOKIE_PATH).takeIf { it.length == 64 }?.let { return it } }
        if (ctx != null) runCatching {
            TorControl.readCookieHex(java.io.File(java.io.File(ctx.filesDir, "tor"), "cookie").absolutePath).takeIf { it.length == 64 }?.let { return it }
        }
        return null
    }

    /** Preferred exit countries, best first (measured by the app's own probes). */
    fun preferredCountries() = listOf("de", "nl", "fi", "se", "ch")

    /** Synthetic SOCKS server for the local Tor instance. */
    fun localCard(socksPort: Int = DEFAULT_SOCKS_PORT): Server =
        LinkParser.parse("socks://127.0.0.1:$socksPort#$CARD_NAME")
            ?: Server(name = CARD_NAME, protocol = "socks", address = "127.0.0.1", port = socksPort, secret = "tor")

    /**
     * Exits the user actually favours: countries seen in their recent server usage
     * (store keys end with a country TLD). Empty = worldwide NEWNYM rotation
     * (the rotator decides by measured latency, see TorProbes).
     */
    fun preferredCountries(servers: List<com.vlesscardvpn.model.Server>): List<String> =
        servers.asSequence()
            .map { it.address.substringBefore('.').takeLast(2).uppercase() }
            .filter { it.length == 2 && it.all(Char::isLetter) }
            .groupingBy { it }.eachCount()
            .filter { it.value >= 3 }
            .keys.take(6)
            .toList()

    /** Public bridge transports the local daemon understands; "none" = plain Tor. */
    val BRIDGES = listOf("none", "obfs4", "snowflake")

    /** Bridge transports built into the APK (Termux additionally provides snowflake). */
    val EMBEDDED_BRIDGES = listOf("none", "obfs4", "webtunnel")

    /** Native dir where the packaged daemon binaries live (same files as for ByeDPI). */
    fun nativeDir(ctx: android.content.Context): String =
        ctx.applicationInfo.nativeLibraryDir

    /** libTor.so ships inside the APK (Tor Browser for Android) — Tor needs no Termux. */
    fun available(ctx: android.content.Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= 26 && java.io.File(nativeDir(ctx), "libTor.so").let { it.isFile && it.canExecute() }

    /** Absolute torrc inside the app's private dir; embedded daemon reads it directly. */
    fun torrcPath(ctx: android.content.Context) =
        java.io.File(java.io.File(ctx.filesDir, "tor"), "torrc").absolutePath

    /**
     * torrc for the embedded daemon: private data dir, loopback-only ports, safe defaults.
     * Bridges use libLyrebird.so from the APK (obfs4/webtunnel); real Bridge lines still
     * come from bridges.torproject.org, as with Termux.
     */
    fun torrcEmbedded(ctx: android.content.Context, controlPort: Int = DEFAULT_CONTROL_PORT,
                      socksPort: Int = DEFAULT_SOCKS_PORT, bridge: String = "none", bridgeLines: List<String> = emptyList(),
                      upstreamHttpPort: Int = DEFAULT_UPSTREAM_PORT, ptProxyPort: Int = DEFAULT_PT_UPSTREAM_PORT): String {
        val dir = java.io.File(ctx.filesDir, "tor")
        return torrcEmbedded(java.io.File(dir, "data").absolutePath, java.io.File(dir, "cookie").absolutePath,
            nativeDir(ctx), controlPort, socksPort, bridge, bridgeLines, upstreamHttpPort, ptProxyPort)
    }

    /** Pure overload — testable on the JVM without an Android context. */
    fun torrcEmbedded(dataDir: String, cookieFile: String, nativeBinDir: String,
                      controlPort: Int = DEFAULT_CONTROL_PORT,
                      socksPort: Int = DEFAULT_SOCKS_PORT, bridge: String = "none",
                      bridgeLines: List<String> = emptyList(), upstreamHttpPort: Int = DEFAULT_UPSTREAM_PORT,
                      ptProxyPort: Int = DEFAULT_PT_UPSTREAM_PORT): String = buildString {
        require(bridge in EMBEDDED_BRIDGES) { "Unsupported embedded Tor transport" }
        require(upstreamHttpPort in 1024..65535 && upstreamHttpPort != socksPort && upstreamHttpPort != controlPort)
        require(ptProxyPort in 1024..65535 && ptProxyPort != socksPort && ptProxyPort != controlPort && ptProxyPort != upstreamHttpPort)
        validateBridgeLines(bridgeLines)
        appendLine("SocksPort 127.0.0.1:$socksPort")
        appendLine("ControlPort 127.0.0.1:$controlPort")
        appendLine("CookieAuthentication 1")
        appendLine("CookieAuthFile $cookieFile")
        appendLine("DataDirectory $dataDir")
        appendLine("SafeLogging 1")
        appendLine("HTTPSProxy 127.0.0.1:$upstreamHttpPort")
        val bin = nativeBinDir
        when (bridge) {
            "obfs4", "webtunnel" -> {
                appendLine("ClientTransportPlugin obfs4,webtunnel exec $bin/libLyrebird.so")
                appendLine("# obfs4/webtunnel: мосты выдаёт bridges.torproject.org — вставьте их в настройках Tor-режима → «Мосты»")
                bridgeLines.map { it.trim() }.filter { it.startsWith("Bridge ") }.forEach { appendLine(it) }
                appendLine("UseBridges 1")
            }
        }
    }

    /** Starts the embedded daemon; returns the process (caller keeps it alive). */
    fun startEmbedded(ctx: android.content.Context, bridge: String = "none",
                      controlPort: Int = DEFAULT_CONTROL_PORT, socksPort: Int = DEFAULT_SOCKS_PORT,
                      bridgeLines: List<String> = emptyList(), upstreamHttpPort: Int = DEFAULT_UPSTREAM_PORT,
                      ptProxyPort: Int = DEFAULT_PT_UPSTREAM_PORT): java.lang.Process {
        if (android.os.Build.VERSION.SDK_INT < 26) error("Embedded Tor requires Android 8+")
        val dir = java.io.File(ctx.filesDir, "tor"); dir.mkdirs()
        java.io.File(dir, "torrc").writeText(torrcEmbedded(ctx, controlPort, socksPort, bridge, bridgeLines, upstreamHttpPort, ptProxyPort))
        val b = java.lang.ProcessBuilder(listOf(java.io.File(nativeDir(ctx), "libTor.so").absolutePath,
            "-f", java.io.File(dir, "torrc").absolutePath))
            .redirectOutput(java.io.File(dir, "tor.log")).redirectErrorStream(true)
        if (bridge != "none") b.environment()["TOR_PT_PROXY"] = "socks5://127.0.0.1:$ptProxyPort"
        return b.start()
    }

    /** True when something already listens on the SOCKS port (Termux tor or our daemon). */
    fun portUp(socksPort: Int = DEFAULT_SOCKS_PORT): Boolean =
        runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 200); true } }.getOrDefault(false)

    /** Starts the embedded daemon unless a local Tor already answers on [socksPort]. */
    @Synchronized
    fun ensureStarted(ctx: android.content.Context, bridge: String = "none",
                      controlPort: Int = DEFAULT_CONTROL_PORT, socksPort: Int = DEFAULT_SOCKS_PORT,
                      bridgeLines: List<String> = emptyList(), upstreamHttpPort: Int = DEFAULT_UPSTREAM_PORT,
                      ptProxyPort: Int = DEFAULT_PT_UPSTREAM_PORT): java.lang.Process? {
        val ticket = generation.get()
        require(bridge in EMBEDDED_BRIDGES) { "Unsupported embedded Tor transport" }
        validateBridgeLines(bridgeLines)
        val desired = torrcEmbedded(ctx, controlPort, socksPort, bridge, bridgeLines, upstreamHttpPort, ptProxyPort)
        val owned = embedded
        if (owned != null) {
            val same = runCatching { java.io.File(torrcPath(ctx)).readText() == desired }.getOrDefault(false)
            val alive = runCatching { owned.exitValue(); false }.getOrDefault(true)
            if (alive && same && !stopping) return null
            owned.destroy()
            // Called from Dispatchers.IO: bounded wait avoids reusing a retiring listener.
            val until = System.nanoTime() + 1_000_000_000L
            while (runCatching { owned.exitValue(); false }.getOrDefault(true) && System.nanoTime() < until) Thread.sleep(25)
            check(runCatching { owned.exitValue(); true }.getOrDefault(false)) { "Previous Tor process is still stopping" }
            embedded = null
        }
        if (portUp(socksPort)) {
            val cookie = readCookieHex(ctx) ?: error("Tor port occupied without control authentication")
            val control = TorControl("127.0.0.1", controlPort)
            try {
                control.connect(cookie)
                val upstream = control.getConf("HTTPSProxy")?.trim('"')
                check(bridge == "none" && upstream == "127.0.0.1:$upstreamHttpPort") { "Tor is already running without the VLESS upstream proxy" }
            } finally { control.close() }
            return null
        }
        check(available(ctx)) { "Embedded Tor unavailable for this device" }
        check(generation.get() == ticket) { "Tor startup cancelled" }
        val process = startEmbedded(ctx, bridge, controlPort, socksPort, bridgeLines, upstreamHttpPort, ptProxyPort)
        stopping = false
        embedded = process
        if (generation.get() != ticket) { process.destroy(); error("Tor startup cancelled") }
        return process
    }

    private fun validateBridgeLines(lines: List<String>) {
        require(lines.size <= 12 && lines.all { line -> line.length <= 4096 && line.none { it.code < 32 || it.code == 127 } }) {
            "Invalid bridge line"
        }
    }

    /** Stops the embedded daemon (Termux tor is managed by Termux itself). */
    fun invalidateStartup() { generation.incrementAndGet() }
    fun stopEmbedded() { generation.incrementAndGet(); stopping = true; embedded?.let { runCatching { it.destroy() } } }
    private val generation = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var stopping = false
    @Volatile private var embedded: java.lang.Process? = null

    /**
     * torrc for a manually managed Tor: Tor stays on loopback and uses the same VLESS proxy.
     * Pluggable transports need TOR_PT_PROXY in their process environment; the app's embedded
     * daemon is the supported bridged path because it sets that environment safely.
     */
    fun torrc(controlPort: Int = DEFAULT_CONTROL_PORT, socksPort: Int = DEFAULT_SOCKS_PORT,
              allowLan: Boolean = false, bridge: String = "none", bridgeLines: List<String> = emptyList(),
              upstreamHttpPort: Int = DEFAULT_UPSTREAM_PORT, ptProxyPort: Int = DEFAULT_PT_UPSTREAM_PORT): String = buildString {
        require(bridge in BRIDGES) { "Unsupported Tor transport" }
        require(upstreamHttpPort in 1024..65535 && upstreamHttpPort != socksPort && upstreamHttpPort != controlPort)
        require(ptProxyPort in 1024..65535 && ptProxyPort != socksPort && ptProxyPort != controlPort && ptProxyPort != upstreamHttpPort)
        validateBridgeLines(bridgeLines)
        val socksBind = if (allowLan) "0.0.0.0:$socksPort" else "127.0.0.1:$socksPort"
        appendLine("SocksPort $socksBind")
        appendLine("ControlPort 127.0.0.1:$controlPort")
        appendLine("CookieAuthentication 1")
        appendLine("SafeLogging 1")
        appendLine("HTTPSProxy 127.0.0.1:$upstreamHttpPort")
        if (bridge != "none") appendLine("# Set TOR_PT_PROXY=socks5://127.0.0.1:$ptProxyPort for the managed transport")
        when (bridge) {
            "snowflake" -> {
                appendLine("ClientTransportPlugin snowflake exec /data/data/com.termux/files/usr/bin/snowflake-client -url https://1098762253-765532153-cloudflare-voip.tor-project.org/ -front cdn.sstatic.net -ice stun:stun.l.google.com:19302,stun:stun.voipgate.com:3478,stun:stun.voys.nl:3478 utls-imitate=hellorandomizedalpn")
                val custom = bridgeLines.map { it.trim() }.filter { it.startsWith("Bridge ") }
                if (custom.isEmpty()) appendLine("Bridge snowflake 192.0.2.3:1 2B280B23E1107BB62ABFC40DDCC8824814F80A72")
                else custom.forEach { appendLine(it) }
                appendLine("UseBridges 1")
            }
            "obfs4" -> {
                appendLine("ClientTransportPlugin obfs4 exec /data/data/com.termux/files/usr/bin/obfs4proxy")
                bridgeLines.map { it.trim() }.filter { it.startsWith("Bridge ") }.forEach { appendLine(it) }
                appendLine("UseBridges 1")
            }
        }
    }
}
