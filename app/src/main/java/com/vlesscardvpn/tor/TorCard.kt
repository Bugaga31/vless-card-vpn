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

    /** torrc for Termux: SOCKS on loopback, authenticated control port, safe defaults. */
    fun torrc(controlPort: Int = DEFAULT_CONTROL_PORT, socksPort: Int = DEFAULT_SOCKS_PORT, allowLan: Boolean = false): String = buildString {
        val socksBind = if (allowLan) "0.0.0.0:$socksPort" else "127.0.0.1:$socksPort"
        appendLine("SocksPort $socksBind")
        appendLine("ControlPort 127.0.0.1:$controlPort")
        appendLine("CookieAuthentication 1")
        appendLine("SafeLogging 1")
    }
}
