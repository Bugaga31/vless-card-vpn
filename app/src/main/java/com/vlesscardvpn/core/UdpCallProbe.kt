package com.vlesscardvpn.core

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object UdpCallProbe {
    const val HOST = "stun.l.google.com"
    const val PORT = 19302
    private const val MAGIC = 0x2112A442
    data class Result(val ok: Boolean, val ms: Int)

    fun bindingRequest(transactionId: ByteArray): ByteArray {
        require(transactionId.size == 12)
        return byteArrayOf(0, 1, 0, 0, 0x21, 0x12, 0xa4.toByte(), 0x42) + transactionId
    }

    fun isBindingResponse(data: ByteArray, length: Int, transactionId: ByteArray): Boolean {
        if (length < 20 || transactionId.size != 12) return false
        val type = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        val bodyLength = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
        val magic = ((data[4].toInt() and 0xff) shl 24) or ((data[5].toInt() and 0xff) shl 16) or
            ((data[6].toInt() and 0xff) shl 8) or (data[7].toInt() and 0xff)
        return type == 0x0101 && bodyLength % 4 == 0 && bodyLength <= length - 20 && magic == MAGIC &&
            transactionId.indices.all { data[8 + it] == transactionId[it] }
    }

    fun socksUdpFrame(host: String, port: Int, payload: ByteArray): ByteArray {
        require(host.isNotBlank() && port in 1..65535)
        val name = host.toByteArray(StandardCharsets.UTF_8)
        require(name.size in 1..255)
        return byteArrayOf(0, 0, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()) + payload
    }

    fun socksUdpPayload(data: ByteArray, length: Int): ByteArray? {
        if (length < 4 || data[0] != 0.toByte() || data[1] != 0.toByte() || data[2] != 0.toByte()) return null
        var pos = 4
        val addressLength = when (data[3].toInt() and 0xff) {
            1 -> 4
            4 -> 16
            3 -> if (length >= 5) (data[4].toInt() and 0xff).also { pos++ } else return null
            else -> return null
        }
        if (addressLength == 0 || pos + addressLength + 2 > length) return null
        pos += addressLength + 2
        return data.copyOfRange(pos, length)
    }

    fun probe(socksPort: Int, user: String = "", pass: String = "", budgetMs: Int = 1800,
              onCancel: ((() -> Unit) -> Unit)? = null): Boolean = measure(socksPort, user, pass, budgetMs, onCancel).ok

    fun measure(socksPort: Int, user: String = "", pass: String = "", budgetMs: Int = 1800,
                onCancel: ((() -> Unit) -> Unit)? = null): Result {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceIn(250, 5000).toLong())
        var udp: DatagramSocket? = null
        val control = Socket()
        fun cancel() { runCatching { udp?.close() }; runCatching { control.close() } }
        onCancel?.invoke(::cancel)
        fun remaining(): Int {
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) throw SocketTimeoutException("UDP check deadline")
            return left.toInt().coerceAtLeast(1)
        }
        fun readFully(input: DataInputStream, target: ByteArray) {
            control.soTimeout = remaining()
            input.readFully(target)
        }
        fun readUnsignedByte(input: DataInputStream): Int {
            control.soTimeout = remaining()
            return input.readUnsignedByte()
        }
        fun readUnsignedShort(input: DataInputStream): Int {
            control.soTimeout = remaining()
            return input.readUnsignedShort()
        }
        val ok = try {
            control.connect(InetSocketAddress("127.0.0.1", socksPort), minOf(800, remaining()))
            val out = control.getOutputStream()
            val input = DataInputStream(control.getInputStream())
            out.write(if (user.isNotEmpty()) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0)); out.flush()
            val method = ByteArray(2); readFully(input, method)
            check(method[0].toInt() == 5)
            if (method[1].toInt() == 2) {
                val u = user.toByteArray(StandardCharsets.UTF_8); val p = pass.toByteArray(StandardCharsets.UTF_8)
                check(u.size in 1..255 && p.size <= 255)
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p); out.flush()
                val auth = ByteArray(2); readFully(input, auth)
                check(auth[0].toInt() == 1 && auth[1].toInt() == 0)
            } else check(method[1].toInt() == 0)
            out.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            val header = ByteArray(4); readFully(input, header)
            check(header[0].toInt() == 5 && header[1].toInt() == 0 && header[2].toInt() == 0)
            val address = when (header[3].toInt() and 0xff) {
                1 -> ByteArray(4).also { readFully(input, it) }
                4 -> ByteArray(16).also { readFully(input, it) }
                3 -> error("SOCKS relay address must be numeric")
                else -> error("Unsupported SOCKS relay address")
            }
            val relayPort = readUnsignedShort(input)
            check(relayPort > 0)
            val relayAddress = InetAddress.getByAddress(address)
            val relay = if (relayAddress.isAnyLocalAddress) InetAddress.getByName("127.0.0.1") else relayAddress
            udp = DatagramSocket().apply { soTimeout = remaining() }
            val transactionId = ByteArray(12).also(SecureRandom()::nextBytes)
            val request = socksUdpFrame(HOST, PORT, bindingRequest(transactionId))
            udp.send(DatagramPacket(request, request.size, relay, relayPort))
            val response = ByteArray(1200)
            val packet = DatagramPacket(response, response.size)
            udp.soTimeout = remaining()
            udp.receive(packet)
            val payload = socksUdpPayload(response, packet.length)
            payload != null && isBindingResponse(payload, payload.size, transactionId)
        } catch (_: Exception) {
            false
        } finally {
            cancel()
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started).toInt().coerceAtLeast(1)
        return Result(ok, elapsed)
    }
}
