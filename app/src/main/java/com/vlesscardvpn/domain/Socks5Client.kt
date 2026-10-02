package com.vlesscardvpn.domain

import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Session-only credentials; never persist or include them in logs. */
data class LocalProbeProxy(val port: Int, val username: String, val password: String) {
    init { require(port in 1024..65535); require(username.isNotBlank() && password.isNotBlank()) }
    override fun toString() = "LocalProbeProxy(loopback, credentials=redacted)"
    companion object {
        fun allocate(): LocalProbeProxy {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            return LocalProbeProxy(port, "probe", UUID.randomUUID().toString().replace("-", ""))
        }
    }
}

/** Remote DNS via SOCKS: no direct fallback, no JVM-global Authenticator. */
internal object Socks5Client {
    fun connect(socket: Socket, proxy: LocalProbeProxy, host: String, port: Int, timeoutMs: Int, onConnected: () -> Unit = {}) {
        require(port in 1..65535)
        val target = host.toByteArray(Charsets.US_ASCII)
        require(target.size in 1..253 && host.none { it.isWhitespace() })
        socket.connect(InetSocketAddress("127.0.0.1", proxy.port), timeoutMs)
        onConnected() // Local TCP succeeded; subsequent failures belong to SOCKS/remote route.
        socket.soTimeout = timeoutMs
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        output.write(byteArrayOf(5, 1, 2)); output.flush()
        check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2) { "SOCKS authentication unavailable" }
        val user = proxy.username.toByteArray(Charsets.UTF_8)
        val password = proxy.password.toByteArray(Charsets.UTF_8)
        require(user.size in 1..255 && password.size in 1..255)
        output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(password.size.toByte()) + password)
        output.flush()
        check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0) { "SOCKS authentication failed" }
        output.write(byteArrayOf(5, 1, 0, 3, target.size.toByte()) + target + byteArrayOf((port shr 8).toByte(), port.toByte()))
        output.flush()
        val version = input.readUnsignedByte(); val status = input.readUnsignedByte(); val reserved = input.readUnsignedByte()
        val type = input.readUnsignedByte()
        check(version == 5 && status == 0 && reserved == 0) { "SOCKS route unavailable" }
        val count = when (type) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("Invalid SOCKS reply") }
        input.readFully(ByteArray(count + 2))
    }
}
