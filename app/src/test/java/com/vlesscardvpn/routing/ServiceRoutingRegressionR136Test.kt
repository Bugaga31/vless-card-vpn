package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServiceRoutingRegressionR136Test {
    private fun server(i: Int) = Server("NL-$i", "vless", "host$i.example", 443, "uuid-$i")
    @Test fun foregroundBudgetDoesNotGrowWithThousandsOfServers() {
        val list = (0..2000).map(::server)
        val selected = ConnectPolicy.candidates(list, emptyList(), emptySet()) { 1 }
        assertEquals(8, selected.size); assertEquals(16, ConnectPolicy.NEXT); assertEquals(24, ConnectPolicy.BACKGROUND)
    }
    @Test fun rememberedAndCloudHintsAreTriedBeforeUnmeasuredCatalog() {
        val list = (0..20).map(::server)
        val selected = ConnectPolicy.candidates(list, listOf(list[15]), setOf(list[17].id)) { it.port }
        assertEquals(list[17].id, selected[0].id); assertEquals(list[15].id, selected[1].id)
        assertEquals(selected.size, selected.distinctBy { it.id }.size)
    }
    @Test fun cloudCannotInjectAnEndpointNotInTheCurrentCatalog() {
        val selected = ConnectPolicy.candidates(listOf(server(1)), listOf(server(999)), emptySet()) { 0 }
        assertEquals(listOf(server(1)), selected)
    }
    @Test fun differentHostsArePreferredToManyTransportVariantsOfOneMachine() {
        val a = server(1); val b = server(2)
        val many = (0..20).map { a.copy(secret = "$it") }
        val selected = ConnectPolicy.candidates(many + b, emptyList(), emptySet()) { 0 }
        assertEquals(b.id, selected[1].id)
    }
    @Test fun serviceChoicesDoNotTreatFailuresAsWorkingRoutes() {
        assertEquals(emptyList<String>(), ServiceRoutes.select(listOf("a" to 0, "b" to -1)))
        assertEquals(listOf("a", "b"), ServiceRoutes.select(listOf("b" to 200, "a" to 100)))
        assertEquals(listOf("b", "a"), ServiceRoutes.select(listOf("b" to 125, "a" to 100), "b"))
        assertEquals(listOf("a", "b"), ServiceRoutes.select(listOf("b" to 900, "a" to 100), "b"))
    }
    @Test fun routesAreNetworkScopedExpireAndDropAfterNoSuccess() {
        assertTrue(ServiceRoutes.update("test-net-136", "gemini", listOf("a" to 100), 1000))
        assertEquals(listOf("a"), ServiceRoutes.snapshot("test-net-136", 1001)["gemini"])
        assertTrue(ServiceRoutes.snapshot("other-test-net", 1001).isEmpty())
        assertTrue(ServiceRoutes.snapshot("test-net-136", 1000 + 25 * 60000).isEmpty())
        assertTrue(ServiceRoutes.update("test-net-136", "gemini", listOf("a" to 0), 2000))
        assertTrue(ServiceRoutes.snapshot("test-net-136", 2001).isEmpty())
    }
    @Test fun configPinsGeminiToVerifiedSelectedEndpointsBeforeGenericAiRule() {
        val a = server(1); val b = server(2)
        val config = JSONObject(XrayConfigBuilder.vpnConfig(listOf(a to null, b to null), Settings(mode = Mode.SERVERS), null,
            serviceRoutes = mapOf("gemini" to listOf(b.id))))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val gemini = (0 until rules.length()).map { rules.getJSONObject(it) }.first { it.optJSONArray("domain")?.toString()?.contains("gemini.google.com") == true }
        assertEquals("proxy-1", gemini.getString("outboundTag"))
    }
    @Test fun staleOrAbsentServerIdsNeverCreateInvalidXrayTags() {
        val a = server(1)
        assertTrue(ServiceRoutes.indices(listOf(a.id), mapOf("gemini" to listOf("missing"))).isEmpty())
        val config = XrayConfigBuilder.vpnConfig(listOf(a to null), Settings(mode = Mode.SERVERS), null,
            serviceRoutes = mapOf("gemini" to listOf("missing")))
        assertFalse(config.contains("service-gemini"))
    }
    @Test fun multipleServiceRoutesHaveBalancerFallbackAndNeverUseDirect() {
        val a = server(1); val b = server(2)
        val config = JSONObject(XrayConfigBuilder.vpnConfig(listOf(a to null, b to null), Settings(mode = Mode.SERVERS), null,
            serviceRoutes = mapOf("gemini" to listOf(b.id, a.id))))
        val bals = config.getJSONObject("routing").getJSONArray("balancers")
        val gemini = (0 until bals.length()).map { bals.getJSONObject(it) }.first { it.getString("tag") == "service-gemini" }
        assertEquals("proxy-1", gemini.getString("fallbackTag")); assertEquals(2, gemini.getJSONArray("selector").length())
    }
    @Test fun mobileCloudReadsAreAllowedButHeavyDownloadsRemainGated() {
        val p = PowerPolicy.evaluate(PowerPolicy.Input(0, metered = true, battery = 80), PowerPolicy.Options())
        assertFalse(p.allowed); assertTrue(p.allowLight)
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0, metered = true, dataSaver = true), PowerPolicy.Options()).allowLight)
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(0, temp = 46.0), PowerPolicy.Options()).allowLight)
    }
    @Test fun parallelSuccessesOnlyClaimOneImmediateStart() {
        val gate = ConnectPolicy.StartGate(); val claims = java.util.concurrent.atomic.AtomicInteger()
        val workers = (0..20).map { Thread { if (gate.claim()) claims.incrementAndGet() }.apply { start() } }
        workers.forEach { it.join() }
        assertEquals(1, claims.get()); assertTrue(gate.started)
    }
    private fun fakeSocks(reply: Boolean, block: (Int, java.util.concurrent.atomic.AtomicInteger) -> Unit) {
        val listener = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val accepts = java.util.concurrent.atomic.AtomicInteger()
        val worker = Thread {
            runCatching {
                listener.accept().use { sock ->
                    accepts.incrementAndGet(); sock.soTimeout = 2000
                    val input = java.io.DataInputStream(sock.getInputStream()); val output = sock.getOutputStream()
                    check(input.readUnsignedByte() == 5); val methods = input.readUnsignedByte(); repeat(methods) { input.readByte() }
                    output.write(byteArrayOf(5, 0)); output.flush()
                    check(input.readUnsignedByte() == 5); input.readByte(); input.readByte()
                    val size = when (input.readUnsignedByte()) { 1 -> 4; 4 -> 16; 3 -> input.readUnsignedByte(); else -> error("atyp") }
                    repeat(size) { input.readByte() }; input.readUnsignedShort()
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                    var tail = ""
                    while (!tail.endsWith("\r\n\r\n")) { tail = (tail + input.readUnsignedByte().toChar()).takeLast(4) }
                    if (reply) { output.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush() }
                    else Thread.sleep(1200)
                }
            }
        }.apply { isDaemon = true; start() }
        try { block(listener.localPort, accepts) } finally { listener.close(); worker.interrupt(); worker.join(2000) }
    }
    @Test fun shortConnectivityProbeDoesNotRequestVideoOrBigFiles() {
        fakeSocks(true) { port, accepts ->
            val p = Tester.probeSocks(port, "http://probe.invalid/check", big = false, youtube = false, attempts = 1, budgetMs = 1500)
            assertTrue(p.works); assertNull(p.bigOk); assertNull(p.ytOk); assertNull(p.tgOk)
            assertEquals(1, accepts.get())
        }
    }
    @Test fun shortProbeTimeoutCannotBeMistakenForConnectivity() {
        fakeSocks(false) { port, _ ->
            val began = System.nanoTime()
            val p = Tester.probeSocks(port, "http://probe.invalid/check", big = false, youtube = false, attempts = 1, budgetMs = 300)
            assertFalse(p.works)
            assertTrue("bounded stalled HTTP", (System.nanoTime() - began) / 1000000 < 3000)
        }
    }

}
