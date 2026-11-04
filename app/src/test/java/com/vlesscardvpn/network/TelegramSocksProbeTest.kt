package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Only loopback fake SOCKS, no requests to real Telegram or user accounts. */
class TelegramSocksProbeTest {
    @Test fun protocolHandshakeTraversesSocksAndValidatesNonce() {
        val error = AtomicReference<Throwable?>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 3000
            val worker = thread(isDaemon = true, name = "test-tg-socks") {
                try { listener.accept().use { s ->
                    s.soTimeout = 3000
                    val input = DataInputStream(s.getInputStream()); val out = s.getOutputStream()
                    assertEquals(5, input.readUnsignedByte())
                    val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                    out.write(byteArrayOf(5, 0)); out.flush()
                    assertEquals(5, input.readUnsignedByte()); assertEquals(1, input.readUnsignedByte())
                    input.readUnsignedByte()
                    val address = when (val type = input.readUnsignedByte()) {
                        1 -> ByteArray(4).also(input::readFully).joinToString(".") { (it.toInt() and 255).toString() }
                        3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.UTF_8)
                        else -> error("unexpected address type $type")
                    }
                    assertTrue(address in setOf("149.154.167.51","149.154.175.50")); assertEquals(443, input.readUnsignedShort())
                    out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 1)); out.flush()
                    val req = ByteArray(42); input.readFully(req)
                    assertEquals(239, req[0].toInt() and 255); assertEquals(10, req[1].toInt())
                    val nonce = req.copyOfRange(26, 42)
                    val response = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(0).putLong(1).putInt(64).putInt(0x05162463).put(nonce).put(ByteArray(16) { 1 })
                        .put(8).put(ByteArray(8).apply { this[7]=15 }).put(ByteArray(3)).putInt(0x1cb5c415).putInt(1).putLong(1).array()
                    out.write(21); out.write(response); out.flush()
                } } catch (e: Throwable) { error.set(e) }
            }
            assertEquals(true, TelegramProbe.check(listener.localPort))
            worker.join(3500)
            error.get()?.let { throw AssertionError("fake SOCKS failed", it) }
            assertFalse(worker.isAlive)
        }
    }
    @Test fun stalledSocksHandshakeHasAHardTotalDeadline() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            listener.soTimeout = 1500
            val worker = thread(isDaemon = true) { runCatching { listener.accept().use { s ->
                // Read without responding to greeting. Client's scheduled close must break its connect.
                s.soTimeout = 1500
                while (s.getInputStream().read() >= 0) { }
            } } }
            val start = System.nanoTime()
            assertNull(TelegramProbe.check(listener.localPort, 250))
            assertTrue("SOCKS deadline exceeded", (System.nanoTime() - start) / 1000000 < 1500)
            worker.join(1600)
            assertFalse(worker.isAlive)
        }
    }
}
