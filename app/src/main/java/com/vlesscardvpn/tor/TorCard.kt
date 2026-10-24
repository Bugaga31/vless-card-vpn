package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.LinkParser

/**
 * Tor as a regular card in the deck: a synthetic SOCKS5 server pointing at the
 * local Termux Tor instance (embedded daemon libTor.so from the APK is started
 * automatically when Termux is absent). Rotations happen behind the card — the
 * app only sees a SOCKS proxy that changes exits over time.
 */
object TorCard {
    const val CARD_NAME = "Tor · ротатор"
    const val DEFAULT_SOCKS_PORT = 9050
    const val DEFAULT_CONTROL_PORT = 9091

    /** Termux data dir where torrc + cookie live. */
    const val TERMUX_TORRC_PATH = "/data/data/com.termux/files/usr/etc/tor/torrc"

    /** Termux data dir holding the ControlPort authentication cookie. */
    const val TERMUX_COOKIE_PATH = "/data/data/com.termux/files/usr/var/lib/tor/control_auth_cookie"

    /** Control cookie: Termux instance first, then the embedded daemon's own cookie. */
    fun readCookieHex(ctx: android.content.Context?): String? {
        runCatching { return TorControl.readCookieHex(TERMUX_COOKIE_PATH) }
        if (ctx != null) runCatching {
            return TorControl.readCookieHex(java.io.File(java.io.File(ctx.filesDir, "tor"), "cookie").absolutePath)
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
        java.io.File(nativeDir(ctx), "libTor.so").let { it.isFile && it.canExecute() }

    /** Absolute torrc inside the app's private dir; embedded daemon reads it directly. */
    fun torrcPath(ctx: android.content.Context) =
        java.io.File(java.io.File(ctx.filesDir, "tor"), "torrc").absolutePath

    /**
     * torrc for the embedded daemon: private data dir, loopback-only ports, safe defaults.
     * Bridges use libLyrebird.so from the APK (obfs4/webtunnel); real Bridge lines still
     * come from bridges.torproject.org, as with Termux.
     */
    fun torrcEmbedded(ctx: android.content.Context, controlPort: Int = DEFAULT_CONTROL_PORT,
                      socksPort: Int = DEFAULT_SOCKS_PORT, bridge: String = "none", bridgeLines: List<String> = emptyList()): String {
        val dir = java.io.File(ctx.filesDir, "tor")
        return torrcEmbedded(java.io.File(dir, "data").absolutePath, java.io.File(dir, "cookie").absolutePath,
            nativeDir(ctx), controlPort, socksPort, bridge, bridgeLines)
    }

    /** Pure overload — testable on the JVM without an Android context. */
    fun torrcEmbedded(dataDir: String, cookieFile: String, nativeBinDir: String,
                      controlPort: Int = DEFAULT_CONTROL_PORT,
                      socksPort: Int = DEFAULT_SOCKS_PORT, bridge: String = "none",
                      bridgeLines: List<String> = emptyList()): String = buildString {
        appendLine("SocksPort 127.0.0.1:$socksPort")
        appendLine("ControlPort 127.0.0.1:$controlPort")
        appendLine("CookieAuthentication 1")
        appendLine("CookieAuthFile $cookieFile")
        appendLine("DataDirectory $dataDir")
        appendLine("SafeLogging 1")
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
                      bridgeLines: List<String> = emptyList()): java.lang.Process {
        val dir = java.io.File(ctx.filesDir, "tor"); dir.mkdirs()
        java.io.File(dir, "torrc").writeText(torrcEmbedded(ctx, controlPort, socksPort, bridge, bridgeLines))
        val b = java.lang.ProcessBuilder(listOf(java.io.File(nativeDir(ctx), "libTor.so").absolutePath,
            "-f", java.io.File(dir, "torrc").absolutePath))
            .redirectOutput(java.io.File(dir, "tor.log")).redirectErrorStream(true)
        return b.start()
    }

    /** True when something already listens on the SOCKS port (Termux tor or our daemon). */
    fun portUp(socksPort: Int = DEFAULT_SOCKS_PORT): Boolean =
        runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), 200); true } }.getOrDefault(false)

    /** Starts the embedded daemon unless a local Tor already answers on [socksPort]. */
    fun ensureStarted(ctx: android.content.Context, bridge: String = "none",
                      controlPort: Int = DEFAULT_CONTROL_PORT, socksPort: Int = DEFAULT_SOCKS_PORT,
                      bridgeLines: List<String> = emptyList()): java.lang.Process? {
        if (!available(ctx) || portUp(socksPort)) return null
        return runCatching { startEmbedded(ctx, bridge, controlPort, socksPort, bridgeLines).also { embedded = it } }.getOrNull()
    }

    /** Stops the embedded daemon (Termux tor is managed by Termux itself). */
    fun stopEmbedded() { embedded?.let { runCatching { it.destroy() } }; embedded = null }
    @Volatile private var embedded: java.lang.Process? = null

    /**
     * torrc for Termux: SOCKS on loopback, authenticated control port, safe defaults.
     * [bridge] picks a pluggable transport: obfs4/snowflake hide the Tor handshake from
     * DPI that blocks the tor protocol itself (the same reason the app masks VLESS).
     */
    fun torrc(controlPort: Int = DEFAULT_CONTROL_PORT, socksPort: Int = DEFAULT_SOCKS_PORT,
              allowLan: Boolean = false, bridge: String = "none", bridgeLines: List<String> = emptyList()): String = buildString {
        val socksBind = if (allowLan) "0.0.0.0:$socksPort" else "127.0.0.1:$socksPort"
        appendLine("SocksPort $socksBind")
        appendLine("ControlPort 127.0.0.1:$controlPort")
        appendLine("CookieAuthentication 1")
        appendLine("SafeLogging 1")
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
