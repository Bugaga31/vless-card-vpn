package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProbeConfigR221Test {
    @Test fun eachTemporarySocksAcceptsUdpOnlyOnLoopbackAndKeepsItsOutbound() {
        val servers=(1..3).map {Server("fixture$it","vless","192.0.2.$it",443,"00000000-0000-0000-0000-00000000000$it")}
        val json=JSONObject(XrayConfigBuilder.testConfig(servers.map{it to null},listOf(18001,18002,18003),null))
        val ins=json.getJSONArray("inbounds");val rules=json.getJSONObject("routing").getJSONArray("rules")
        assertEquals(3,ins.length())
        for(i in servers.indices) {
            val inbound=ins.getJSONObject(i);val settings=inbound.getJSONObject("settings")
            assertEquals("127.0.0.1",inbound.getString("listen"));assertEquals("127.0.0.1",settings.getString("ip"))
            assertTrue(settings.getBoolean("udp"));assertEquals("noauth",settings.getString("auth"))
            assertEquals("v$i",rules.getJSONObject(i).getString("outboundTag"))
            assertEquals("t$i",rules.getJSONObject(i).getJSONArray("inboundTag").getString(0))
        }
    }
    @Test fun oldApiOnlyProofCannotBeUsedAsGatewayHelloProof() {
        val p=AutoCheckProfile.of(setOf("discord"))
        assertFalse(AutoChecks.fresh(p.key+"@800",p,800,100,101))
        assertTrue(AutoChecks.fresh(AutoChecks.key(p,800),p,800,100,101))
        val basic=AutoCheckProfile.of(emptyList())
        assertEquals("https@800",AutoChecks.key(basic,800))
    }
    @Test fun selectedServicesStartBeforeFirstConnectivityRequestWithoutDirectFallback() {
        val source=listOf(File("src/main/java/com/vlesscardvpn/core/Tester.kt"),File("app/src/main/java/com/vlesscardvpn/core/Tester.kt")).first{it.exists()}.readText()
        val method=source.substringAfter("    fun probeSocks(").substringBefore("    // Media downloads")
        val connect=method.indexOf("var best = Long.MAX_VALUE")
        for(name in listOf("val ytF =","val callF =","val discordF =")) assertTrue(name,method.indexOf(name) in 0 until connect)
        assertTrue(method.contains("DiscordRouteProbe.check(mediaClient"))
        assertTrue(method.contains("YouTubeAccessProbe.check { boundedCall(mediaClient"))
        assertTrue(method.contains("System.nanoTime()).coerceAtLeast(1)"))
    }
}
