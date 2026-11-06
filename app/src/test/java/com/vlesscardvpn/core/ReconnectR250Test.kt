package com.vlesscardvpn.core

import com.vlesscardvpn.xray.Masks
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** 1.2.136: no false «связь пропала», no outage blamed on servers, no reconnect for a refreshed but unchanged link. */
class ReconnectR250Test {
    private fun source(n: String) = listOf(File("src/main/java/com/vlesscardvpn/$n"), File("app/src/main/java/com/vlesscardvpn/$n")).first { it.exists() }.readText()
    private fun watchdog() = source("vpn/TunnelService.kt").substringAfter("    private fun watchdog()").substringBefore("    private var standbyJob")

    // ---------- RouteLiveness ----------
    @Test fun onlyNetworkUpRouteSilentIsADeadRoute() {
        assertEquals(RouteLiveness.Verdict.ALIVE, RouteLiveness.verdict(alive = true, physical = false))
        assertEquals(RouteLiveness.Verdict.ALIVE, RouteLiveness.verdict(alive = true, physical = true))
        assertEquals(RouteLiveness.Verdict.OFFLINE, RouteLiveness.verdict(alive = false, physical = false))
        assertEquals(RouteLiveness.Verdict.DEAD, RouteLiveness.verdict(alive = false, physical = true))
    }
    @Test fun anyHttpStatusIsAnAnswer() {
        for (code in listOf(100, 200, 204, 301, 403, 429, 503, 599)) assertTrue("$code", RouteLiveness.answered(code))
        for (code in listOf(-1, 0, 99, 600)) assertFalse("$code", RouteLiveness.answered(code))
    }
    @Test fun confirmationUsesTheCheckUrlAndOneIndependentEndpoint() {
        assertEquals(listOf("https://check.example/generate_204", RouteLiveness.GSTATIC), RouteLiveness.urls("https://check.example/generate_204"))
        assertEquals(listOf(RouteLiveness.GSTATIC, RouteLiveness.CLOUDFLARE), RouteLiveness.urls(RouteLiveness.GSTATIC))
        assertEquals(listOf(RouteLiveness.GSTATIC, RouteLiveness.CLOUDFLARE), RouteLiveness.urls("http://plain.example/"))
        assertTrue(RouteLiveness.CONFIRM_MS in 4000L..8000L)
    }
    @Test fun physicalTargetsAreBoundedAndKeepFixedWitnesses() {
        val t = RouteLiveness.targets(listOf("a" to 443, "a" to 443, "b" to 8443, "" to 1, "c" to 0, "d" to 1, "e" to 2))
        assertTrue(t.containsAll(RouteLiveness.PHYSICAL_TARGETS))
        assertEquals(RouteLiveness.PHYSICAL_TARGETS.size + RouteLiveness.MAX_SERVER_TARGETS, t.size)
        assertTrue(("a" to 443) in t && ("b" to 8443) in t && ("d" to 1) in t)
        assertFalse(t.any { it.first.isEmpty() || it.second !in 1..65535 })
        assertFalse("UDP endpoints cannot witness a TCP path", "wireguard" in RouteLiveness.TCP_PROTOCOLS || "hysteria2" in RouteLiveness.TCP_PROTOCOLS)
    }

    @Test fun physicalCheckFindsOneReachableTargetQuickly() {
        ServerSocket(0).use { open ->
            val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
            val started = System.nanoTime()
            assertTrue(Tester.physicalInternet(listOf("127.0.0.1" to closed, "127.0.0.1" to open.localPort), 1000))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2500)
            assertFalse(Tester.physicalInternet(listOf("127.0.0.1" to closed), 500))
            assertFalse(Tester.physicalInternet(emptyList(), 500))
        }
    }

    /** Minimal SOCKS5 (no auth, CONNECT) so [Tester.alive] runs its real OkHttp path through a local proxy. */
    private fun socks(): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        c.use { client ->
                            val i = client.getInputStream(); val o = client.getOutputStream()
                            i.read(); val n = i.read(); repeat(n) { i.read() }
                            o.write(byteArrayOf(5, 0)); o.flush()
                            i.read(); i.read(); i.read()
                            val host = when (i.read()) {
                                1 -> ByteArray(4).also { i.read(it) }.joinToString(".") { (it.toInt() and 255).toString() }
                                3 -> String(ByteArray(i.read()).also { i.read(it) })
                                else -> error("unsupported")
                            }
                            val port = (i.read() shl 8) or i.read()
                            Socket().use { up ->
                                up.connect(InetSocketAddress(host, port), 2000)
                                o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
                                val t = Thread { runCatching { up.getInputStream().copyTo(o) } }.apply { isDaemon = true; start() }
                                runCatching { i.copyTo(up.getOutputStream()) }
                                t.join(3000)
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        return server
    }
    private fun http(status: Int, silent: Boolean = false): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        c.use {
                            val r = it.getInputStream().bufferedReader()
                            while (r.readLine()?.isNotEmpty() == true) Unit
                            if (silent) Thread.sleep(5000)
                            else it.getOutputStream().apply { write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    @Test fun aliveAcceptsAnyAnswerThroughTheRouteAndStaysBounded() {
        socks().use { proxy -> http(503).use { busy -> http(204, silent = true).use { mute ->
            assertTrue(Tester.alive(proxy.localPort, listOf("http://127.0.0.1:${mute.localPort}/", "http://127.0.0.1:${busy.localPort}/"), 3000))
            val started = System.nanoTime()
            assertFalse(Tester.alive(proxy.localPort, listOf("http://127.0.0.1:${mute.localPort}/"), 800))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
        } } }
        val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        assertFalse(Tester.alive(closed, listOf("http://127.0.0.1:1/"), 800))
        assertFalse(Tester.alive(closed, emptyList(), 800))
    }

    // ---------- Watchdog / recovery structure ----------
    @Test fun lostRouteIsConfirmedBeforeBlameAndDetectorSurvives() {
        val s = watchdog()
        assertTrue(s.contains("confirmLoss(port, settings.testUrl)"))
        assertTrue(s.contains("RouteLiveness.Verdict.OFFLINE"))
        assertTrue(s.contains("if (ok && offline) { offline = false; miss = 0; verifySoon(0, suspectedFailure = true); continue }"))
        assertFalse(s.contains("if (ok && offline) { verifySoon(0); break }"))
        assertTrue(s.contains("if (hardRestart(\"сервер перестал отвечать\")) break"))
        assertTrue(s.contains("if (dnsMiss >= 2) { if (hardRestart(\"DNS перестал отвечать\")) break"))
        assertTrue(s.contains("miss >= 2"))
        val all = source("vpn/TunnelService.kt")
        assertTrue(all.contains("private fun hardRestart(why: String, force: Boolean = false): Boolean"))
        val rescue = all.substringAfter("private fun recoverServerRoute()").substringBefore("private fun heal()")
        assertTrue(rescue.indexOf("if (!reachable)") in 0 until rescue.indexOf("Actions.autoRescue("))
        assertTrue(rescue.contains("verifySoon(0,suspectedFailure=true)"))
    }
    @Test fun networkRefreshAdoptsAdditionsBeforeCancellingWork() {
        val s = source("vpn/TunnelService.kt").substringAfter("private fun queueNetworkRefresh()").substringBefore("private suspend fun liveHandoffProbe")
        assertTrue(s.indexOf("handoff.adopt(snapshot)") in 0 until s.indexOf("handoff.update(snapshot)"))
        assertTrue(s.indexOf("handoff.update(snapshot)") < s.indexOf("watchJob?.cancel()"))
        assertTrue(source("core/Net.kt").contains("NetworkPath.transport("))
    }

    // ---------- NetworkPath.transport ----------
    private fun transport(addresses: List<String> = listOf("192.0.2.10/24", "2001:db8:1:2::abcd/64", "fe80::1/64"),
                          gateways: List<String> = listOf("192.0.2.1", "fe80::1"), iface: String = "wlan0") = NetworkPath.transport(iface, addresses, gateways)
    @Test fun refreshedLinkKeepsItsTransportPath() {
        val base = transport()
        assertEquals(base, transport(addresses = listOf("2001:db8:1:2::abcd/64", "192.0.2.10/24", "fe80::1/64")))
        // A rotated temporary IPv6 address in the same prefix, lifetimes/flags appended by LinkAddress.toString()
        assertEquals(base, transport(addresses = listOf("192.0.2.10/24 flags 128 scope 0", "2001:db8:1:2:1234:5678:9abc:def0/64", "fe80::1/64")))
        // Another link-local router address on IPv6 does not matter
        assertEquals(base, transport(gateways = listOf("192.0.2.1", "fe80::2")))
        assertEquals(base, transport(addresses = listOf("192.0.2.10/24", "2001:db8:1:2::abcd/64", "fe80::99/64")))
        assertFalse(base.contains("192.0.2") || base.contains("2001:db8"))
    }
    @Test fun realTransportChangesStillCount() {
        val base = transport()
        assertNotEquals(base, transport(addresses = listOf("192.0.2.11/24", "2001:db8:1:2::abcd/64")))
        assertNotEquals(base, transport(addresses = listOf("192.0.2.10/24", "2001:db8:1:3::abcd/64")))
        assertNotEquals(base, transport(gateways = listOf("192.0.2.254")))
        assertNotEquals(base, transport(iface = "rmnet0"))
    }
    @Test fun onlyPureAdditionsExtendAPath() {
        val v4 = transport(addresses = listOf("192.0.2.10/24"))
        val dual = transport()
        assertTrue(NetworkPath.extends(v4, dual))
        assertFalse(NetworkPath.extends(dual, v4))
        assertFalse(NetworkPath.extends(v4, transport(addresses = listOf("192.0.2.11/24", "2001:db8:1:2::1/64"))))
        assertFalse(NetworkPath.extends("", dual)); assertFalse(NetworkPath.extends(v4, ""))
    }
    @Test fun invalidLiteralsAreIgnoredWithoutLookups() {
        val base = transport(addresses = listOf("192.0.2.10/24"))
        assertEquals(base, transport(addresses = listOf("192.0.2.10/24", "not-an-address", "1:2:3", "999.1.1.1/24", "gg::1/64", "")))
        assertNotEquals(base, transport(addresses = listOf("192.0.2.10/24", "::ffff:198.51.100.7/96")))
    }
    @Test fun legacyRevisionIsUnchanged() {
        assertNotEquals(NetworkPath.revision("wlan0", 1500, listOf("a"), listOf("r"), listOf("d1")),
            NetworkPath.revision("wlan0", 1500, listOf("a"), listOf("r"), listOf("d2")))
    }

    // ---------- NetworkHandoff.adopt ----------
    @Test fun additionIsAdoptedWithoutANewRevision() {
        val v4 = transport(addresses = listOf("192.0.2.10/24")); val dual = transport()
        val gate = NetworkHandoff()
        val ticket = gate.update(NetworkHandoff.Snapshot("100", "wifi", v4))!!
        assertTrue(gate.adopt(NetworkHandoff.Snapshot("100", "wifi", dual)))
        assertTrue(gate.valid(ticket.revision))
        assertNull("already current", gate.update(NetworkHandoff.Snapshot("100", "wifi", dual)))
        assertFalse("same snapshot is not an adoption", gate.adopt(NetworkHandoff.Snapshot("100", "wifi", dual)))
    }
    @Test fun anythingElseStillGoesThroughUpdate() {
        val v4 = transport(addresses = listOf("192.0.2.10/24")); val dual = transport()
        val gate = NetworkHandoff(); gate.seed(NetworkHandoff.Snapshot("100", "wifi", dual))
        assertFalse("lost element", gate.adopt(NetworkHandoff.Snapshot("100", "wifi", v4)))
        assertFalse("other handle", gate.adopt(NetworkHandoff.Snapshot("101", "wifi", dual)))
        assertFalse("other profile", gate.adopt(NetworkHandoff.Snapshot("100", "cell", dual)))
        assertFalse("offline", gate.adopt(null))
        assertFalse(NetworkHandoff().adopt(NetworkHandoff.Snapshot("100", "wifi", dual)))
        assertNotNull(gate.update(NetworkHandoff.Snapshot("100", "wifi", v4)))
    }

    // ---------- NetworkIdentity ----------
    @Test fun sameHandleKeepsTheMoreCompleteIdentity() {
        val m = NetworkIdentity()
        val full = "Моб.: Beeline · CELL_25099"
        assertEquals(full, m.stable("101", full, NetworkIdentity.cellularRank("Beeline", "25099")))
        assertEquals(full, m.stable("101", "Моб.: оператор · CELL_abcd", NetworkIdentity.cellularRank("", null)))
        assertEquals(full, m.stable("101", "Моб.: оператор · CELL_25099", NetworkIdentity.cellularRank("", "25099")))
        assertEquals("another network keeps its own key", "Моб.: MTS · CELL_25001", m.stable("102", "Моб.: MTS · CELL_25001", 3))
        assertEquals("unknown handle is never remembered", "x", m.stable("", "x", 0))
    }
    @Test fun moreCompleteIdentityReplacesAWeakerOne() {
        val m = NetworkIdentity()
        assertEquals("Моб.: оператор · CELL_abcd", m.stable("7", "Моб.: оператор · CELL_abcd", NetworkIdentity.cellularRank("", null)))
        assertEquals("Моб.: Tele2 · CELL_25020", m.stable("7", "Моб.: Tele2 · CELL_25020", NetworkIdentity.cellularRank("Tele2", "25020")))
        assertEquals("Wi-Fi · WIFI_a", m.stable("8", "Wi-Fi · WIFI_a", NetworkIdentity.wifiRank("192.168.1.1", "")))
        assertEquals("DNS flicker keeps the key", "Wi-Fi · WIFI_a", m.stable("8", "Wi-Fi · WIFI_b", NetworkIdentity.wifiRank("192.168.1.1", "<unknown ssid>")))
        assertEquals("Wi-Fi · WIFI_c", m.stable("8", "Wi-Fi · WIFI_c", NetworkIdentity.wifiRank("192.168.1.1", "\"Home\"")))
    }
    @Test fun identityMemoryIsBounded() {
        val m = NetworkIdentity(capacity = 3)
        for (i in 1..10) m.stable("h$i", "k$i", 1)
        assertEquals("evicted handle is computed again", "new", m.stable("h1", "new", 0))
        assertEquals("k10", m.stable("h10", "other", 1))
    }

    // ---------- Required services ----------
    private fun profile(vararg s: String) = AutoCheckProfile.of(s.toList())
    private val good = Probe(100, true, true, tgOk = true, tgMs = 100, discordOk = true, callsOk = true, instagramOk = true, stableOk = true)
    @Test fun singleServiceSampleIsConfirmedOnceBeforeSwitching() {
        assertTrue(RequiredServiceRecovery.needsConfirmation(profile("voice"), good.copy(callsOk = false)))
        assertTrue(RequiredServiceRecovery.needsConfirmation(profile("discord", "telegram"), good.copy(discordOk = false)))
        assertTrue(RequiredServiceRecovery.needsConfirmation(profile("stability"), good.copy(stableOk = false, serviceNotes = mapOf("stability" to "STABILITY_INCOMPLETE"))))
        assertFalse("Telegram was already retried", RequiredServiceRecovery.needsConfirmation(profile("telegram", "voice"), good.copy(tgOk = false, callsOk = false)))
        assertFalse("quality series keep their own confirmation", RequiredServiceRecovery.needsConfirmation(profile("stability"), good.copy(stableOk = false, serviceNotes = mapOf("stability" to "STABILITY_SPREAD"))))
        assertFalse("nothing failed", RequiredServiceRecovery.needsConfirmation(profile("voice", "discord"), good))
        assertFalse("no HTTPS: the dead-route path owns it", RequiredServiceRecovery.needsConfirmation(profile("voice"), good.copy(realMs = 0, callsOk = false)))
        val s = source("vpn/TunnelService.kt")
        assertTrue(s.indexOf("RequiredServiceRecovery.needsConfirmation(profile, p)") in 0 until s.indexOf("RequiredServiceRecovery.failed(profile,p)"))
    }

    // ---------- Masks ----------
    @Test fun zeroPauseRecordCutsExistForEveryFingerprint() {
        for (shape in listOf("hello.duo", "hello.tri", "hello.sni")) for (fp in Masks.FINGERPRINTS) {
            val m = Masks.byId("$fp.$shape"); assertNotNull("$fp.$shape", m); assertNotNull(Masks.byId("$fp.$shape.b"))
            assertEquals("tlshello", m!!.packets)
            assertTrue(m.delays.split(',').all { it == "0-0" })
            assertEquals(m.lengths.split(',').size, m.delays.split(',').size)
        }
        assertEquals(0, Masks.cost(Masks.byId("chrome.hello.duo")!!))
        assertEquals(0, Masks.cost(Masks.byId("chrome.hello.tri")!!))
        assertEquals(1, Masks.cost(Masks.byId("chrome.hello.sni")!!))
        val reality = com.vlesscardvpn.model.Server("r", "vless", "1.2.3.4", 443, "11111111-1111-1111-1111-111111111111", security = "reality", sni = "www.microsoft.com", pbk = "k")
        assertTrue(Masks.compatible(Masks.byId("chrome.hello.duo")!!, reality))
    }
    @Test fun moreCoverNamesNeedPinnedTlsAndAreSearchedEarly() {
        val pinned = com.vlesscardvpn.model.Server("p", "vless", "vc.example.com", 443, "11111111-1111-1111-1111-111111111111", security = "tls", sni = "vc.example.com", pcs = "ab".repeat(32))
        val plain = pinned.copy(pcs = "")
        for (d in Masks.MORE_SNI) {
            assertTrue(d in Masks.WHITE_POOL)
            val m = Masks.byId("vc.sni.$d")!!; val fast = Masks.byId("vc.sni.$d.fast")!!
            assertTrue(Masks.compatible(m, pinned)); assertTrue(Masks.compatible(fast, pinned)); assertFalse(Masks.compatible(m, plain))
            assertEquals(0, Masks.cost(m))
        }
        val order = Masks.searchOrder(true, pinned).map { it.id }
        // Curated ahead of the untried bulk of the same weight
        assertTrue(order.indexOf("chrome.hello.duo") in 0 until order.indexOf("qq.hello.duo"))
        assertTrue(order.indexOf("firefox.hello.sni") in 0 until order.indexOf("qq.hello.sni"))
        assertTrue(order.indexOf("vc.sni.wildberries.ru.fast") >= 0)
        assertEquals(Masks.ALL.size, Masks.ALL.map { it.id }.toSet().size)
    }
}
