package com.vlesscardvpn.core

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded unauthenticated req_pq_multi/resPQ reachability, NOT an account login or server authentication.
 * Official built-in DCs: telegramdesktop/tdesktop, mtproto_dc_options.cpp.
 * All traffic uses the supplied local SOCKS listener; never direct fallback or account data.
 */
object TelegramProbe {
    private val dc = listOf("149.154.167.51", "149.154.175.50")
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tg-probe-timeout").apply { isDaemon = true } }
    private val random = SecureRandom()

    internal fun request(nonce: ByteArray, now: Long): ByteArray {
        require(nonce.size == 16)
        val body = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0).putLong((now / 1000 shl 32) or ((now % 1000) * 4000000 and 0xfffffffcL))
            .putInt(20).putInt(0xbe7e8ef1.toInt()).put(nonce).array()
        return byteArrayOf(0xef.toByte(), 10) + body
    }

    internal fun validResponse(bytes: ByteArray, nonce: ByteArray): Boolean = runCatching {
        if (nonce.size != 16 || bytes.size !in 76..4096 || bytes.size % 4 != 0) return false
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.long != 0L) return false
        b.long // server message id
        if (b.int != bytes.size - 20 || b.int != 0x05162463) return false
        val got = ByteArray(16); b.get(got)
        if (!java.security.MessageDigest.isEqual(got, nonce)) return false
        b.position(b.position() + 16) // server nonce
        val len = b.get().toInt() and 255
        if (len !in 1..8 || b.remaining() < len + (4 - (len + 1) % 4) % 4 + 8) return false
        b.position(b.position() + len + (4 - (len + 1) % 4) % 4)
        if (b.int != 0x1cb5c415) return false // TL vector of RSA key fingerprints
        val count = b.int
        count in 1..16 && b.remaining() == count * 8
    }.getOrDefault(false)

    data class Result(val ok: Boolean?, val reason: String)

    /** Injectable clock/transport for slow-handshake and budget regressions. No network work is faked in production. */
    internal fun evaluate(budgetMs: Long, elapsed: () -> Long, attempt: (String, Long) -> Result): Result {
        val deadline = elapsed() + budgetMs.coerceIn(0, ServiceProbePolicy.TELEGRAM_MS)
        var unknown: Result? = null
        var last = Result(null,"deadline")
        for (host in dc) {
            if (Thread.currentThread().isInterrupted) return Result(null,"cancelled")
            val left = deadline - elapsed()
            if (left < 200) return unknown ?: Result(null,"deadline")
            last = attempt(host,minOf(ServiceProbePolicy.DC_MS,left))
            if (last.ok == true) return last
            if (last.reason == "cancelled") return last
            if (last.ok == null) unknown = last
        }
        return unknown ?: last
    }

    fun check(port: Int, budgetMs: Long = ServiceProbePolicy.TELEGRAM_MS): Boolean? = checkResult(port,budgetMs).ok

    /** The deadline includes SOCKS + actual tunnel handshake + MTProto; 1.5s used to cut off working routes. */
    fun checkResult(port: Int, budgetMs: Long = ServiceProbePolicy.TELEGRAM_MS, onSocket: (Socket) -> Unit = {}): Result {
        if (port !in 1..65535 || budgetMs < 200) return Result(null,"deadline")
        return evaluate(budgetMs, { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }) { host, allowance ->
            val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            onSocket(socket)
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(allowance)
            val timeout = timer.schedule({ runCatching { socket.close() } }, allowance, TimeUnit.MILLISECONDS)
            try {
                socket.use { s ->
                    s.soTimeout = allowance.toInt()
                    s.connect(InetSocketAddress(host,443),allowance.toInt())
                    val nonce = ByteArray(16).also(random::nextBytes)
                    s.getOutputStream().write(request(nonce,System.currentTimeMillis()))
                    val input = java.io.DataInputStream(s.getInputStream())
                    val first = input.readUnsignedByte()
                    val words = if (first == 127) input.readUnsignedByte() or (input.readUnsignedByte() shl 8) or (input.readUnsignedByte() shl 16) else first
                    val valid = if (words !in 19..1024) false else {
                        val frame = ByteArray(words * 4); input.readFully(frame); validResponse(frame,nonce)
                    }
                    Result(valid,if (valid) "mtproto-response" else "mtproto-invalid")
                }
            } catch (e: Exception) {
                when {
                    Thread.currentThread().isInterrupted -> Result(null,"cancelled")
                    e is java.net.SocketTimeoutException || System.nanoTime() >= end -> Result(null,"mtproto-timeout")
                    else -> Result(false,"mtproto-connect")
                }
            } finally { timeout.cancel(false); runCatching { socket.close() } }
        }
    }
}
