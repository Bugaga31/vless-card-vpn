package com.vlesscardvpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlesscardvpn.core.ByeDpiRunner
import com.vlesscardvpn.domain.ByeDpiArgs
import com.vlesscardvpn.domain.ByeDpiPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
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
 */
@RunWith(AndroidJUnit4::class)
class ByeDpiMaskLoopbackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun maskFakeIsDroppedAndRealHelloArrives() = exercise { it.start(ByeDpiPreset.MASK_FAKE, "ya.ru") }
    @Test fun maskSplitFakeIsDroppedAndRealHelloArrives() = exercise { it.start(ByeDpiPreset.MASK_SPLIT_FAKE, "vk.com") }
    @Test fun customMaskLineIsDroppedAndRealHelloArrives() =
        exercise { it.startCustom(ByeDpiArgs.parse("-d1 -f-1 -t8 -S -n {sni} -Qr", "gosuslugi.ru").getOrThrow()) }

    private fun clientHello(): ByteArray {
        val engine = SSLContext.getInstance("TLS").apply { init(null, null, null) }.createSSLEngine("vpn.test.local", 443)
        engine.useClientMode = true
        engine.sslParameters = engine.sslParameters.apply { serverNames = listOf(SNIHostName("vpn.test.local")) }
        engine.beginHandshake()
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }.also { assertTrue("No ClientHello", it.size > 100 && it[0] == 0x16.toByte()) }
    }

    private fun exercise(start: suspend (ByeDpiRunner) -> Int) = runBlocking {
        val hello = clientHello()
        val runner = ByeDpiRunner(context)
        val pool = Executors.newSingleThreadExecutor()
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            try {
                val port = start(runner)
                val received = pool.submit<ByteArray> {
                    server.accept().use { s -> s.soTimeout = 10_000
                        ByteArray(hello.size).also { DataInputStream(s.getInputStream()).readFully(it) } }
                }
                Socket().use { socks ->
                    socks.soTimeout = 10_000
                    socks.connect(InetSocketAddress("127.0.0.1", port), 3000)
                    val o = socks.getOutputStream(); val i = DataInputStream(socks.getInputStream())
                    o.write(byteArrayOf(5, 1, 0)); o.flush()
                    assertArrayEquals(byteArrayOf(5, 0), ByteArray(2).also { i.readFully(it) })
                    o.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (server.localPort shr 8).toByte(), server.localPort.toByte())); o.flush()
                    val reply = ByteArray(10).also { i.readFully(it) }
                    assertEquals("SOCKS connect failed", 0, reply[1].toInt())
                    o.write(hello); o.flush()
                    val got = received.get(15, TimeUnit.SECONDS)
                    // If the fake leaked, the server would see the masking SNI instead of the real ClientHello.
                    assertArrayEquals("Server did not receive the real ClientHello (fake leaked or TCP_MD5SIG unsupported)", hello, got)
                }
            } finally { runner.close(); pool.shutdownNow() }
        }
    }
}
