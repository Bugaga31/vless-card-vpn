package com.vlesscardvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.domain.ByeDpiArgs
import com.vlesscardvpn.domain.ByeDpiPreset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

/**
 * Fake-SNI masking on the Android kernel itself: an in-device loopback server must receive exactly the
 * real ClientHello. Loopback ignores TTL, so this proves the fake is dropped by the TCP MD5 option
 * (and that TCP_MD5SIG is available). The 10.0.2.2 fixtures cannot test this: SLIRP accepts any fake.
 * Failure messages start with a stable marker so CI can classify them.
 */
@RunWith(AndroidJUnit4::class)
class ByeDpiMaskLoopbackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun controlSplitReachesLoopbackIntact() = exercise(listOf("--split", "1+s"), control = true)
    @Test fun maskFakeIsDroppedAndRealHelloArrives() = exercise(ByeDpiPreset.MASK_FAKE.arguments(1080, "ya.ru").drop(8))
    @Test fun maskSplitFakeIsDroppedAndRealHelloArrives() = exercise(ByeDpiPreset.MASK_SPLIT_FAKE.arguments(1080, "vk.com").drop(8))
    @Test fun customMaskLineIsDroppedAndRealHelloArrives() =
        exercise(ByeDpiArgs.parse("-d1 -f-1 -t8 -S -n {sni} -Qr", "gosuslugi.ru").getOrThrow())

    private fun clientHello(): ByteArray {
        val engine = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine("vpn.test.local", 443)
        engine.useClientMode = true
        engine.sslParameters = engine.sslParameters.apply { serverNames = listOf(SNIHostName("vpn.test.local")) }
        engine.beginHandshake()
        val out = ByteBuffer.allocate(32768)
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    private fun exercise(desync: List<String>, control: Boolean = false) {
        val hello = try { clientHello().also { check(it.size > 100 && it[0] == 0x16.toByte()) { "no ClientHello" } } }
            catch (t: Throwable) { fail("HARNESS ClientHello: $t"); return }
        val executable = File(context.applicationInfo.nativeLibraryDir, "libbyedpi.so")
        val log = File(context.cacheDir, "byedpi-mask-${System.nanoTime()}.log")
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val child = ProcessBuilder(listOf(executable.absolutePath) + ByeDpiArgs.loopbackPrefix(port) + desync)
            .redirectOutput(File("/dev/null")).redirectError(log).start()
        val pool = Executors.newSingleThreadExecutor()
        var got: ByteArray? = null
        var error: Throwable? = null
        try {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                val received = pool.submit<ByteArray> {
                    server.accept().use { s -> s.soTimeout = 8_000
                        val buf = ByteArray(hello.size); var n = 0
                        val input = s.getInputStream()
                        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                        buf.copyOf(n) }
                }
                try {
                    var socks: Socket? = null
                    for (attempt in 0 until 50) {
                        // A Socket that failed to connect is closed by Java; use a fresh one per attempt.
                        socks = runCatching { Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 200) } }.getOrNull()
                        if (socks != null) break
                        Thread.sleep(50)
                    }
                    checkNotNull(socks) { "ByeDPI not ready, alive=${child.isAlive}" }.use {
                        it.soTimeout = 8_000
                        val o = it.getOutputStream(); val i = DataInputStream(it.getInputStream())
                        o.write(byteArrayOf(5, 1, 0)); o.flush()
                        ByteArray(2).also { b -> i.readFully(b) }
                        o.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (server.localPort shr 8).toByte(), server.localPort.toByte())); o.flush()
                        val reply = ByteArray(10).also { b -> i.readFully(b) }
                        check(reply[1].toInt() == 0) { "SOCKS reply ${reply[1]}" }
                        o.write(hello); o.flush()
                        got = received.get(12, TimeUnit.SECONDS)
                    }
                } catch (t: Throwable) { error = t }
            }
        } finally {
            child.destroy(); runCatching { child.waitFor(500, TimeUnit.MILLISECONDS) }; pool.shutdownNow()
        }
        val stderr = runCatching { log.readText().take(400) }.getOrDefault("").also { log.delete() }
        if (got?.contentEquals(hello) == true) return
        val detail = "err=$error got=${got?.size}/${hello.size} stderr=$stderr"
        when {
            control -> fail("HARNESS control: $detail")
            "TCP_MD5SIG" in stderr -> fail("MD5SIG_UNSUPPORTED $detail")
            "splice" in stderr -> fail("SPLICE_FAILED $detail")
            got != null && got!!.isNotEmpty() -> fail("FAKE_LEAKED $detail")
            else -> fail("OTHER_MASK $detail")
        }
    }
}
