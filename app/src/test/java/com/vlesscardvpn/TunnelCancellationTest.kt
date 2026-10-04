package com.vlesscardvpn

import com.vlesscardvpn.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TunnelCancellationTest {
    private enum class Pause { GREETING, AUTH, CONNECT, TLS }
    private class StalledProxy(private val pause: Pause) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val endpoint = LocalProbeProxy(listener.localPort, "user", "password")
        val reached = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        @Volatile private var peer: Socket? = null
        private val thread = Thread {
            try {
                listener.accept().use { socket ->
                    peer = socket; socket.soTimeout = 7000
                    val input = DataInputStream(socket.getInputStream()); val output = socket.getOutputStream()
                    check(input.readUnsignedByte() == 5)
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    fun waitForClose() {
                        reached.countDown()
                        while (input.read() != -1) { /* Discard; no payload is logged or stored. */ }
                        peerClosed.countDown()
                    }
                    if (pause == Pause.GREETING) { waitForClose(); return@use }
                    output.write(byteArrayOf(5, 2)); output.flush()
                    check(input.readUnsignedByte() == 1)
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    input.readFully(ByteArray(input.readUnsignedByte()))
                    if (pause == Pause.AUTH) { waitForClose(); return@use }
                    output.write(byteArrayOf(1, 0)); output.flush()
                    check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
                    check(input.readUnsignedByte() == 3)
                    input.readFully(ByteArray(input.readUnsignedByte())); input.readUnsignedShort()
                    if (pause == Pause.CONNECT) { waitForClose(); return@use }
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                    // A TLS byte proves the client reached the handshake before cancellation.
                    check(input.read() != -1)
                    waitForClose()
                }
            } catch (_: Exception) { /* Test assertions use latches, not daemon exceptions. */ }
        }.apply { isDaemon = true; start() }
        override fun close() { listener.close(); peer?.close(); thread.join(1000) }
    }
    private suspend fun CountDownLatch.arrives() = withContext(Dispatchers.IO) { await(2, TimeUnit.SECONDS) }

    @Test fun cancellingDuringSocksGreetingClosesSocket(): Unit = runBlocking { cancellation(Pause.GREETING) }
    @Test fun cancellingDuringSocksAuthClosesSocket(): Unit = runBlocking { cancellation(Pause.AUTH) }
    @Test fun cancellingDuringRemoteConnectClosesSocket(): Unit = runBlocking { cancellation(Pause.CONNECT) }
    @Test fun cancellingDuringTlsClosesSocket(): Unit = runBlocking { cancellation(Pause.TLS) }
    private suspend fun cancellation(pause: Pause) = coroutineScope {
        StalledProxy(pause).use { proxy ->
            val probe = async { TunnelHealthChecker.probe(proxy.endpoint, "https://fixture.invalid/", 204, 5000) }
            assertTrue("Probe must reach the selected protocol stage", proxy.reached.arrives())
            withTimeout(2000) { probe.cancelAndJoin() }
            assertTrue(probe.isCancelled)
            assertTrue("Cancellation must close the actual socket", proxy.peerClosed.arrives())
        }
    }
    @Test fun deadlineAtEachSocksStageClosesSocketAndReportsStage(): Unit = runBlocking {
        for (pause in listOf(Pause.GREETING, Pause.AUTH, Pause.CONNECT)) {
            StalledProxy(pause).use { proxy ->
                val result = TunnelHealthChecker.probe(proxy.endpoint, "https://fixture.invalid/", 204, 500)
                assertEquals(DiagnosticFailure.TIMEOUT, result.failure)
                assertEquals(DiagnosticFailure.PROXY, result.stage)
                assertNull(result.httpCode)
                assertTrue("Deadline must close the actual socket", proxy.peerClosed.arrives())
            }
        }
    }
}
