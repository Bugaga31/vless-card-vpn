package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.junit.Assert.*
import org.junit.Test

class FullCloudSelectionR139Test {
    private val now=1_000_000L
    private fun server(name:String)=Server(name,"vless","$name.invalid",443,name)
    private fun state(speed:Int=1000)=ServerState(realMs=50,kbps=speed,speedNetwork="wifi",speedMask="",speedCheckedAt=now,
        serviceNetwork="wifi",serviceMask="",serviceCheckedAt=now,ytOk=true,tgOk=true)
    @Test fun russianFlagAnywhereOverridesEvenForeignFirstFlag() {
        assertFalse(AutoEligibility.allowed(server("🇳🇱 test 🇷🇺")))
        assertFalse(AutoEligibility.allowed(server("RU-1")))
        assertFalse(AutoEligibility.allowed(server("Москва")))
        assertTrue(AutoEligibility.allowed(server("🇩🇪 test")))
        assertTrue(AutoEligibility.allowed(server("unknown")))
    }
    @Test fun cleanupRemovesRuFromListSelectionAndRememberedChainButPreservesOthers() {
        val ru=server("🇷🇺 node");val de=server("🇩🇪 node")
        val st=AppState(listOf(ru,de),mapOf(ru.id to state().copy(selected=true),de.id to state()),Settings(netServers=mapOf("wifi" to listOf(ru.id,de.id))))
        val clean=AutoEligibility.clean(st)
        assertEquals(listOf(de),clean.servers);assertFalse(ru.id in clean.states)
        assertEquals(listOf(de.id),clean.settings.netServers["wifi"])
        assertEquals(clean,AutoEligibility.clean(clean));assertEquals(10,AutoEligibility.TOP)
    }
    @Test fun legacyOrDifferentNetworkMaskAndExpiredProofAreUnknown() {
        val s=state()
        assertEquals(0,AutoEligibility.serviceRank(s,"wifi",now))
        assertEquals(1,AutoEligibility.serviceRank(s,"cell",now))
        assertEquals(1,AutoEligibility.serviceRank(s.copy(maskId="chrome.h4"),"wifi",now))
        assertEquals(1,AutoEligibility.serviceRank(s,"wifi",now+300001))
        assertEquals(1,AutoEligibility.serviceRank(s.copy(serviceCheckedAt=0),"wifi",now))
        assertEquals(1,AutoEligibility.serviceRank(s,"wifi",now-1))
    }
    @Test fun bothServicesWinOverFasterBrokenRouteAndAllowBackgroundRepair() {
        val good=state(1000);val bad=state(90000).copy(tgOk=false)
        assertTrue(ServerChoice.compareDownload(good,bad,"wifi",now)<0)
        assertTrue(ServerChoice.downloadUpgrade(bad,good,"wifi",now))
        assertEquals(listOf(good),AutoEligibility.servicePool(listOf(bad,good),{it},"wifi",now))
    }
    @Test fun tenWorkingRoutesActuallyReachTheConnectedPoolNotJustOnePrimary() {
        val routes=(1..14).map { server("DE-$it") };val states=routes.mapIndexed { i,s -> s.id to state((i+1)*1000) }.toMap()
        val connected=AutoEligibility.connectedPool(routes,{states.getValue(it.id)},"wifi",now,false)
        assertEquals(10,connected.size);assertEquals(routes.last(),connected.first())
        assertEquals(routes.takeLast(10).reversed(),connected)
        assertEquals(3,AutoEligibility.connectedPool(routes.take(3),{states.getValue(it.id)},"wifi",now,false).size)
        assertTrue(AutoEligibility.connectedPool(listOf(server("🇷🇺 blocked")),{state()},"wifi",now,false).isEmpty())
        val config=org.json.JSONObject(XrayConfigBuilder.vpnConfig(connected.map { it to null },Settings(mode=Mode.SERVERS),null))
        val out=config.getJSONArray("outbounds")
        assertEquals(10,(0 until out.length()).count { out.getJSONObject(it).optString("tag").startsWith("proxy-") })
        assertTrue(config.getJSONObject("routing").getJSONArray("balancers").length()>0)
    }
    @Test fun serviceProvenanceSurvivesDiskRoundTrip() {
        val s=state();val loaded=ServerState.fromJson(s.toJson())
        assertEquals(s.serviceNetwork,loaded.serviceNetwork);assertEquals(s.serviceMask,loaded.serviceMask)
        assertEquals(s.serviceCheckedAt,loaded.serviceCheckedAt);assertEquals(0,AutoEligibility.serviceRank(loaded,"wifi",now))
    }
    @Test fun telegramIpRulePrecedesRussianDirectAndExplicitUserOverrideStillWins() {
        val rules=org.json.JSONObject(XrayConfigBuilder.vpnConfig(listOf(server("DE-1") to null),Settings(alwaysDirect=listOf("telegram.org")),null)).getJSONObject("routing").getJSONArray("rules")
        val rows=(0 until rules.length()).map {rules.getJSONObject(it)}
        val tg=rows.indexOfFirst {it.optJSONArray("ip")?.toString()?.contains("geoip:telegram")==true}
        val ru=rows.indexOfFirst {it.optJSONArray("ip")?.toString()?.contains("geoip:ru")==true}
        val user=rows.indexOfFirst {it.optJSONArray("domain")?.toString()?.contains("domain:telegram.org")==true && it.optString("outboundTag")=="direct"}
        assertTrue(tg>=0 && ru>tg);assertTrue(user>=0 && user<tg)
    }
}
