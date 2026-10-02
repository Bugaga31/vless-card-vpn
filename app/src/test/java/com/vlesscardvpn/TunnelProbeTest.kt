package com.vlesscardvpn

import com.vlesscardvpn.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory

class TunnelProbeTest {
    private class Proxy(private val targetPort: Int, private val allowAuth: Boolean = true) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val endpoint = LocalProbeProxy(server.localPort, "test-user", "test-password")
        val requestedHost = AtomicReference<String>()
        private val sockets = mutableListOf<Socket>()
        init {
            Thread {
                try {
                    val incoming = server.accept(); synchronized(sockets) { sockets.add(incoming) }
                    incoming.soTimeout = 3000
                    val input = DataInputStream(incoming.getInputStream()); val output = incoming.getOutputStream()
                    check(input.readUnsignedByte() == 5)
                    val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
                    check(methods.contentEquals(byteArrayOf(2)))
                    output.write(byteArrayOf(5, if (allowAuth) 2 else 0)); output.flush()
                    if (!allowAuth) { incoming.close(); return@Thread }
                    check(input.readUnsignedByte() == 1)
                    val user = ByteArray(input.readUnsignedByte()); input.readFully(user)
                    val pass = ByteArray(input.readUnsignedByte()); input.readFully(pass)
                    check(String(user) == endpoint.username && String(pass) == endpoint.password)
                    output.write(byteArrayOf(1, 0)); output.flush()
                    check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0)
                    check(input.readUnsignedByte() == 3) // domain forwarded to native proxy, no direct DNS fallback
                    val name = ByteArray(input.readUnsignedByte()); input.readFully(name)
                    requestedHost.set(String(name)); input.readUnsignedShort()
                    val target = Socket("127.0.0.1", targetPort); synchronized(sockets) { sockets.add(target) }
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                    fun forward(a: Socket, b: Socket) = Thread {
                        try { a.getInputStream().copyTo(b.getOutputStream()) } catch (_: Exception) {}
                        finally { try { b.close() } catch (_: Exception) {} }
                    }.apply { isDaemon = true; start() }
                    forward(incoming, target); forward(target, incoming)
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
        }
        override fun close() {
            server.close(); synchronized(sockets) { sockets.forEach { try { it.close() } catch (_: Exception) {} } }
        }
    }
    private fun fixtures(): Triple<MockWebServer, SSLSocketFactory, HeldCertificate> {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer(); server.useHttps(serverTls.sslSocketFactory(), false); server.start()
        return Triple(server, clientTls.sslSocketFactory(), certificate)
    }
    @Test fun authenticatedSocksWithRemoteDnsAndVerifiedTlsReceives204() = runBlocking {
        val (server, tls, _) = fixtures()
        server.use { it.enqueue(MockResponse().setResponseCode(204))
            Proxy(it.port).use { proxy ->
                val result = TunnelHealthChecker.probe(proxy.endpoint, "https://localhost:${it.port}/generate_204", 204, 3000, tls)
                assertEquals(null, result.error); assertEquals(204, result.httpCode)
                assertTrue(result.latencyMs!! > 0); assertEquals("localhost", proxy.requestedHost.get())
            }
        }
    }
    @Test fun redirectAndCaptivePageCannotCountAsSuccess() = runBlocking {
        val (server, tls, _) = fixtures()
        server.use { it.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.org"))
            Proxy(it.port).use { proxy ->
                val result = TunnelHealthChecker.probe(proxy.endpoint, "https://localhost:${it.port}/", 204, 3000, tls)
                assertNotNull(result.error); assertEquals(302, result.httpCode)
            }
        }
    }
    @Test fun hostnameMismatchIsRejectedEvenWithTrustedCertificate() = runBlocking {
        val (server, tls, _) = fixtures()
        server.use { it.enqueue(MockResponse().setResponseCode(204))
            Proxy(it.port).use { proxy ->
                val result = TunnelHealthChecker.probe(proxy.endpoint, "https://wrong.example.org:${it.port}/", 204, 3000, tls)
                assertNotNull(result.error); assertNull(result.httpCode)
            }
        }
    }
    @Test fun cannotFallBackToUnauthenticatedProxy() = runBlocking {
        val (server, tls, _) = fixtures()
        server.use { Proxy(it.port, false).use { proxy ->
            val result = TunnelHealthChecker.probe(proxy.endpoint, "https://localhost:${it.port}/", 204, 3000, tls)
            assertNotNull(result.error); assertNull(result.httpCode)
        } }
    }
    @Test fun absentProxyNeverUsesDirectInternet() = runBlocking {
        val result = TunnelHealthChecker.probe(null, "https://example.org/", 204, 100)
        assertNotNull(result.error); assertNull(result.httpCode)
    }
    @Test fun stalledTlsRetainsHandshakeStage(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { target ->
            val accepted = AtomicReference<Socket>()
            Thread { try { accepted.set(target.accept()) } catch (_: Exception) {} }.apply { isDaemon = true; start() }
            try {
                Proxy(target.localPort).use { proxy ->
                    val result = TunnelHealthChecker.probe(proxy.endpoint, "https://localhost:${target.localPort}/", 204, 800)
                    assertEquals(DiagnosticFailure.TIMEOUT, result.failure)
                    assertEquals(DiagnosticFailure.TLS, result.stage)
                    assertTrue(result.latencyMs!! > 0)
                }
            } finally { accepted.get()?.close() }
        }
    }
    @Test fun stalledHttpRetainsResponseStage(): Unit = runBlocking {
        val (server, tls, _) = fixtures()
        server.use {
            it.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(3, TimeUnit.SECONDS))
            Proxy(it.port).use { proxy ->
                val result = TunnelHealthChecker.probe(proxy.endpoint, "https://localhost:${it.port}/", 204, 1000, tls)
                assertNotNull(it.takeRequest(1, TimeUnit.SECONDS))
                assertEquals(DiagnosticFailure.TIMEOUT, result.failure)
                assertEquals(DiagnosticFailure.HTTP, result.stage)
            }
        }
    }
    @Test fun stalledProxyIsClosedAtDeadline(): Unit = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val accepted = AtomicReference<Socket>()
            Thread { try { accepted.set(server.accept()) } catch (_: Exception) {} }.apply { isDaemon = true; start() }
            val started = System.nanoTime()
            val result = TunnelHealthChecker.probe(LocalProbeProxy(server.localPort, "user", "password"), "https://example.org/", 204, 200)
            assertNotNull(result.error); assertEquals(DiagnosticFailure.TIMEOUT, result.failure); assertEquals(DiagnosticFailure.PROXY, result.stage)
            assertTrue(result.latencyMs!! > 0); assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
            accepted.get()?.close()
        }
    }
}
