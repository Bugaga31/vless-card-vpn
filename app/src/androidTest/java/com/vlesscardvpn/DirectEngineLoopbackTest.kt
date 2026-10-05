package com.vlesscardvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.ByeDpiRunner
import com.vlesscardvpn.domain.DirectStrategies
import com.vlesscardvpn.domain.DirectStrategy
import com.vlesscardvpn.domain.DpiEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

/**
 * Every "без сервера" engine/strategy with the shipped Android binaries and the production argv: the
 * TLS handshake bytes must reach a loopback server intact after reassembling TLS records (tlsrec splits
 * them) and the reply must come back. tpws refuses local targets by design; the test-only environment
 * switch VCVPN_TPWS_ALLOW_LOCAL lifts that for this loopback fixture only.
 */
@RunWith(AndroidJUnit4::class)
class DirectEngineLoopbackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun tpwsBinaryShipped() = assertTrue("TPWS_MISSING libtpws.so not packaged", ByeDpiRunner.available(context, DpiEngine.TPWS))
    @Test fun allBuiltInStrategiesRelayIntact() {
        val failed = DirectStrategies.BUILT_IN.filter { !it.masked }.mapNotNull { s -> runCatching { exercise(s) }.exceptionOrNull()?.let { "${s.id}: ${it.message}" } }
        assertTrue("DIRECT_RELAY " + failed.joinToString("; ").take(1500), failed.isEmpty())
    }
    @Test fun vcardStealthRelaysIntact() = exercise(DirectStrategies.VCARD_STEALTH)
    /** Own masking family incl. the masked cascade (its fakes only fire after a DPI failure, never here). */
    @Test fun vcardOwnFamilyRelaysIntact() {
        val failed = DirectStrategies.OWN.mapNotNull { s -> runCatching { exercise(s) }.exceptionOrNull()?.let { "${s.id}: ${it.message}" } }
        assertTrue("VCARD_RELAY " + failed.joinToString("; ").take(1500), failed.isEmpty())
    }
    @Test fun tpwsRestartOnSamePort() = runBlocking {
        val runner = ByeDpiRunner(context)
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        try {
            DirectStrategies.BUILT_IN.filter { it.engine == DpiEngine.TPWS }.take(3).forEach { s ->
                assertEquals(port, runner.startDirect(s, port, allowLocalTargets = true))
            }
            assertEquals(port, runner.startDirect(DirectStrategies.BUILT_IN.first(), port))
        } finally { runner.close() }
    }

    private fun clientHello(): ByteArray {
        val engine = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine("www.youtube.com", 443)
        engine.useClientMode = true
        engine.sslParameters = engine.sslParameters.apply { serverNames = listOf(SNIHostName("www.youtube.com")) }
        engine.beginHandshake()
        val out = ByteBuffer.allocate(32768)
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    /** Concatenated TLS record payloads, or null while incomplete. */
    private fun recordPayload(data: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(); var i = 0
        while (i + 5 <= data.size) {
            val len = ((data[i + 3].toInt() and 0xff) shl 8) or (data[i + 4].toInt() and 0xff)
            if (data[i] != 0x16.toByte() || i + 5 + len > data.size) return null
            out.write(data, i + 5, len); i += 5 + len
        }
        return if (i == data.size) out.toByteArray() else null
    }

    private fun exercise(strategy: DirectStrategy) = runBlocking {
        val hello = clientHello()
        val expected = checkNotNull(recordPayload(hello)) { "HARNESS ClientHello" }
        val runner = ByeDpiRunner(context)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            runner.startDirect(strategy, port, "ya.ru", allowLocalTargets = true)
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                val received = pool.submit<ByteArray?> {
                    server.accept().use { s -> s.soTimeout = 8_000
                        val buf = ByteArrayOutputStream(); val chunk = ByteArray(4096); val input = s.getInputStream()
                        var payload: ByteArray? = null
                        while (payload == null || payload.size < expected.size) {
                            val r = input.read(chunk); if (r < 0) break
                            buf.write(chunk, 0, r); payload = recordPayload(buf.toByteArray())
                        }
                        s.getOutputStream().apply { write("PONG".toByteArray()); flush() }
                        payload
                    }
                }
                Socket("127.0.0.1", port).use {
                    it.soTimeout = 8_000
                    val o = it.getOutputStream(); val i = DataInputStream(it.getInputStream())
                    o.write(byteArrayOf(5, 1, 0)); o.flush()
                    ByteArray(2).also { b -> i.readFully(b) }
                    o.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (server.localPort shr 8).toByte(), server.localPort.toByte())); o.flush()
                    val reply = ByteArray(10).also { b -> i.readFully(b) }
                    check(reply[1].toInt() == 0) { "SOCKS reply ${reply[1]}" }
                    o.write(hello); o.flush()
                    val got = received.get(12, TimeUnit.SECONDS)
                    check(got != null && got.contentEquals(expected)) { "handshake altered: ${got?.size}/${expected.size}" }
                    val pong = ByteArray(4).also { b -> i.readFully(b) }
                    check(String(pong) == "PONG") { "no reply" }
                }
            }
        } finally { runner.close(); pool.shutdownNow() }
    }
}
