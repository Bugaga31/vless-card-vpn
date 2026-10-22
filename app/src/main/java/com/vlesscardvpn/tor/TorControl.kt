package com.vlesscardvpn.tor

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal Tor ControlPort client (tor-rotator-vless parity): AUTHENTICATE cookie,
 * SIGNAL NEWNYM, SETCONF ExitNodes, GETINFO status/circuit-established.
 * Speaks the raw line protocol — no dependencies beyond a TCP socket.
 */
class TorControl(private val host: String = "127.0.0.1", private val port: Int = DEFAULT_CONTROL_PORT) {
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var out: java.io.OutputStream? = null

    fun connect(cookieHex: String) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = 5000
        socket = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
        out = s.getOutputStream()
        val auth = command("AUTHENTICATE", cookieHex)
        if (!auth.startsWith("250")) throw IOException("Tor auth failed: $auth")
    }

    fun signalNewNym() {
        val r = command("SIGNAL", "NEWNYM")
        if (!r.startsWith("250")) throw IOException("NEWNYM failed: $r")
    }

    fun setExitCountries(countries: List<String>) {
        if (countries.isEmpty()) return
        val value = countries.joinToString(",") { "{${it.lowercase()}}" }
        val r = command("SETCONF", "ExitNodes=\"$value\"")
        if (!r.startsWith("250")) throw IOException("SETCONF failed: $r")
    }

    /** true when at least one circuit is established. */
    fun circuitEstablished(): Boolean = try {
        command("GETINFO", "status/circuit-established").contains("status/circuit-established=1")
    } catch (e: IOException) {
        false
    }

    fun close() {
        runCatching { command("QUIT") }
        runCatching { socket?.close() }
        socket = null; reader = null; out = null
    }

    /** Sends a command, returns the joined multi-line reply (last-data line prefixed by '250 '). */
    @Throws(IOException::class)
    fun command(vararg parts: String): String {
        val s = socket ?: throw IOException("not connected")
        val line = parts.joinToString(" ")
        out!!.write((line + "\r\n").toByteArray(Charsets.US_ASCII))
        out!!.flush()
        val sb = StringBuilder()
        while (true) {
            val resp = reader!!.readLine() ?: throw IOException("EOF from control port")
            sb.append(resp).append('\n')
            // mid lines "250-...", terminal "250 ..." / "5xx ..."
            if (resp.length < 4 || resp[3] != '-') break
        }
        return sb.toString().trim()
    }

    companion object {
        const val DEFAULT_CONTROL_PORT = 9051

        /** Reads the Tor control auth cookie file and returns its hex (Termux path by default). */
        fun readCookieHex(path: String = TERMUX_COOKIE_PATH): String =
            java.io.File(path).readBytes().joinToString("") { "%02x".format(it) }

        const val TERMUX_COOKIE_PATH = "/data/data/com.termux/files/usr/var/lib/tor/control_auth_cookie"
    }
}
