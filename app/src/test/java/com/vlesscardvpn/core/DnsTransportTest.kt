package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DnsTransportTest {
    /** Actual TCP SOCKS negotiation + UDP health query, all on loopback. */
    private fun fixture(auth: Boolean = false, transform: (ByteArray) -> ByteArray): Boolean {
        val loop = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 1, loop).use { tcp -> DatagramSocket(0, loop).use { udp ->
            tcp.soTimeout = 3000; udp.soTimeout = 3000
            val executor = Executors.newSingleThreadExecutor()
            val server = executor.submit {
                tcp.accept().use { socket ->
                    socket.soTimeout = 3000
                    val i = DataInputStream(socket.getInputStream()); val o = socket.getOutputStream()
                    val hello = ByteArray(3); i.readFully(hello)
                    check(hello.contentEquals(byteArrayOf(5, 1, if (auth) 2 else 0)))
                    o.write(byteArrayOf(5, if (auth) 2 else 0)); o.flush()
                    if (auth) {
                        check(i.readUnsignedByte() == 1)
                        val u = ByteArray(i.readUnsignedByte()); i.readFully(u)
                        val p = ByteArray(i.readUnsignedByte()); i.readFully(p)
                        check(String(u) == "u" && String(p) == "p")
                        o.write(byteArrayOf(1, 0)); o.flush()
                    }
                    val associate = ByteArray(10); i.readFully(associate)
                    check(associate.take(4) == listOf<Byte>(5, 3, 0, 1))
                    o.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (udp.localPort shr 8).toByte(), udp.localPort.toByte())); o.flush()
                    val packet = DatagramPacket(ByteArray(1500), 1500); udp.receive(packet)
                    val q = DnsWire.payload(packet.data, packet.length) ?: error("invalid health query")
                    val response = q.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1 } +
                        byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 30, 0, 4, 8, 8, 8, 8)
                    val body = transform(response)
                    val framed = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53) + body
                    udp.send(DatagramPacket(framed, framed.size, packet.socketAddress))
                    // Keep the associate alive until the probe consumes its response.
                    runCatching { i.readByte() }
                }
            }
            try {
                val result = Tester.dnsViaSocks(tcp.localPort, if (auth) "u" else "", if (auth) "p" else "", budgetMs = 1500)
                server.get(3, TimeUnit.SECONDS)
                return result
            } finally { executor.shutdownNow(); executor.awaitTermination(3, TimeUnit.SECONDS) }
        } }
    }
    @Test fun realSocksUdpAnswerAccepted() { assertTrue(fixture { it }) }
    @Test fun passwordProtectedSocksUdpAnswerAccepted() { assertTrue(fixture(auth = true) { it }) }
    @Test fun realSocksWrongIdNotPass() { assertFalse(fixture { it.apply { this[0] = (this[0].toInt() xor 1).toByte() } }) }
    @Test fun realSocksTruncatedResponseNotPass() { assertFalse(fixture { it.copyOf(8) }) }
    @Test fun cancelledSocketDoesNotContinue() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            assertFalse(Tester.dnsViaSocks(server.localPort, budgetMs = 1500, onCancel = { close -> close() }))
        }
    }
}
