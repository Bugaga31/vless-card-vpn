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
        assertEquals(clean,AutoEligibility.clean(clean));assertEquals(8,AutoEligibility.TOP)
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
    @Test fun eightWorkingRoutesActuallyReachTheConnectedPoolNotJustOnePrimary() {
        val routes=(1..14).map { server("DE-$it") };val states=routes.mapIndexed { i,s -> s.id to state((i+1)*1000) }.toMap()
        val connected=AutoEligibility.connectedPool(routes,{states.getValue(it.id)},"wifi",now,false)
        assertEquals(8,connected.size);assertEquals(routes.last(),connected.first())
        assertEquals(routes.takeLast(8).reversed(),connected)
        assertEquals(3,AutoEligibility.connectedPool(routes.take(3),{states.getValue(it.id)},"wifi",now,false).size)
        assertTrue(AutoEligibility.connectedPool(listOf(server("🇷🇺 blocked")),{state()},"wifi",now,false).isEmpty())
        val config=org.json.JSONObject(XrayConfigBuilder.vpnConfig(connected.map { it to null },Settings(mode=Mode.SERVERS),null))
        val out=config.getJSONArray("outbounds")
        assertEquals(8,(0 until out.length()).count { out.getJSONObject(it).optString("tag").startsWith("proxy-") })
        assertTrue(config.getJSONObject("routing").getJSONArray("balancers").length()>0)
    }
    @Test fun serviceProvenanceSurvivesDiskRoundTrip() {
        val s=state();val loaded=ServerState.fromJson(s.toJson())
        assertEquals(s.serviceNetwork,loaded.serviceNetwork);assertEquals(s.serviceMask,loaded.serviceMask)
        assertEquals(s.serviceCheckedAt,loaded.serviceCheckedAt);assertEquals(0,AutoEligibility.serviceRank(loaded,"wifi",now))
    }
    @Test fun telegramAndSavedDirectExceptionsRemainOnServer() {
        val r=org.json.JSONObject(XrayConfigBuilder.vpnConfig(listOf(server("DE-1") to null),Settings(alwaysDirect=listOf("telegram.org"),ruDirect=true),null)).getJSONObject("routing").getJSONArray("rules")
        val rows=(0 until r.length()).map(r::getJSONObject)
        assertTrue(rows.any {it.optJSONArray("ip")?.toString()?.contains("geoip:telegram")==true && it.optString("outboundTag")=="proxy-0"})
        assertFalse(rows.any {it.optString("outboundTag")=="direct" && (it.optJSONArray("ip")?.toString()?.contains("geoip:ru")==true || it.optJSONArray("domain")?.toString()?.contains("telegram.org")==true)})
    }
    @Test fun apkChecksAreHourlyAndStillRateLimitedAfterErrors() {
        val t=100_000_000L
        assertFalse(UpdatePolicy.checkDue(t+3_599_999L,t,t))
        assertTrue(UpdatePolicy.checkDue(t+3_600_000L,t,t))
        assertFalse(UpdatePolicy.checkDue(t+899_999L,0,t))
    }
    @Test fun automaticDpiPlanIncludesEveryAvailableEngineAndCustomStrategy() {
        val settings=Settings(byeDpiArgs="--disorder 1")
        val all=DpiStrategies.plan(settings,"wifi",true)
        assertTrue(all.any { it.engine==DpiEngine.BYEDPI })
        assertTrue(all.any { it.engine==DpiEngine.TPWS })
        assertTrue(all.any { it.engine==DpiEngine.XRAY })
        assertTrue(all.any { it.id==DpiStrategies.CUSTOM_ID })
        assertFalse(DpiStrategies.plan(settings,"wifi",false).any { it.engine==DpiEngine.TPWS })
    }
    @Test fun vkYandexProfilesNeverReplaceRealitySni() {
        val reality=server("🇩🇪 node").copy(security="reality",sni="original.example",pbk="public-key")
        for (host in listOf("vk.com","yandex.ru","vkvideo.ru")) {
            val mask=com.vlesscardvpn.xray.Masks.byId("vc.sni.$host")!!
            assertFalse(com.vlesscardvpn.xray.Masks.compatible(mask,reality))
            assertFalse(com.vlesscardvpn.xray.Masks.searchOrder(true,reality).any { it.sni.isNotEmpty() })
        }
        for (id in listOf("BYEDPI#VK_ADAPTIVE","BYEDPI#YANDEX_ADAPTIVE")) {
            val strategy=DpiStrategies.byId(id,Settings())!!
            assertTrue(ByeDpiArgs.parse(strategy.args.joinToString(" "),"ya.ru").isSuccess)
            assertTrue(strategy.adaptive)
            assertTrue(strategy.argv(1080,"ya.ru").contains("--fake-sni"))
        }
    }
}
