package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.LinkParser

/**
 * Tor as a regular card in the deck: a synthetic SOCKS5 server pointing at the
 * local Termux Tor instance. Rotations happen behind the card — the app only
 * sees a SOCKS proxy that changes exits over time.
 */
object TorCard {
    const val CARD_NAME = "Tor · ротатор"
    const val DEFAULT_SOCKS_PORT = 9050
    const val DEFAULT_CONTROL_PORT = 9091

    /** Termux data dir where torrc + cookie live. */
    const val TERMUX_TORRC_PATH = "/data/data/com.termux/files/usr/etc/tor/torrc"

    /** Termux data dir holding the ControlPort authentication cookie. */
    const val TERMUX_COOKIE_PATH = "/data/data/com.termux/files/usr/var/lib/tor/control_auth_cookie"

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

    /**
     * torrc for Termux: SOCKS on loopback, authenticated control port, safe defaults.
     * [bridge] picks a pluggable transport: obfs4/snowflake hide the Tor handshake from
     * DPI that blocks the tor protocol itself (the same reason the app masks VLESS).
     */
    fun torrc(controlPort: Int = DEFAULT_CONTROL_PORT, socksPort: Int = DEFAULT_SOCKS_PORT,
              allowLan: Boolean = false, bridge: String = "none"): String = buildString {
        val socksBind = if (allowLan) "0.0.0.0:$socksPort" else "127.0.0.1:$socksPort"
        appendLine("SocksPort $socksBind")
        appendLine("ControlPort 127.0.0.1:$controlPort")
        appendLine("CookieAuthentication 1")
        appendLine("SafeLogging 1")
        when (bridge) {
            "snowflake" -> {
                appendLine("ClientTransportPlugin snowflake exec /data/data/com.termux/files/usr/bin/snowflake-client -url https://1098762253-765532153-cloudflare-voip.tor-project.org/ -front cdn.sstatic.net -ice stun:stun.l.google.com:19302,stun:stun.voipgate.com:3478,stun:stun.voys.nl:3478 utls-imitate=hellorandomizedalpn")
                appendLine("Bridge snowflake 192.0.2.3:1 2B280B23E1107BB62ABFC40DDCC8824814F80A72")
                appendLine("UseBridges 1")
            }
            "obfs4" -> {
                appendLine("ClientTransportPlugin obfs4 exec /data/data/com.termux/files/usr/bin/obfs4proxy")
                appendLine("# obfs4: мосты выдаёт bridges.torproject.org — вставьте их строками «Bridge obfs4 …» ниже (настройки Tor-режима → «Получить мосты»)")
                appendLine("UseBridges 1")
            }
        }
    }
}
