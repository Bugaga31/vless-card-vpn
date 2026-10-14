package com.vlesscardvpn

import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdaptiveCloudSelectionTest {
    private val link="vless://00000000-0000-0000-0000-000000000001@node.example:443?security=reality&pbk=test&sni=example.com"
    private fun manifest(now: Long=1000000) = JSONObject().put("schema",1).put("stamp",now/1000).put("expires",now/1000+3600)
        .put("nodes",org.json.JSONArray().put(JSONObject().put("link",link).put("tier","silver").put("cloudScore",.75)))
    @Test fun manifestRejectsExpiredFutureAndOverflowDates() {
        for (o in listOf(manifest().put("expires",999),manifest().put("stamp",99999999),manifest().put("stamp",Long.MAX_VALUE))) {
            try { CloudControl.parse(o.toString(),1000000);fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun manifestOnlyImportsWorkingTiersAndNoFabricatedLocalStats() {
        val s=CloudControl.parse(manifest().toString(),1000000);assertEquals(1,s.entries.size);assertEquals(.75,s.entries[0].score,.001)
        assertEquals(0,ServerState().evidenceFor("net").checks)
        val o=manifest();o.getJSONArray("nodes").getJSONObject(0).put("tier","quarantine")
        assertTrue(CloudControl.parse(o.toString(),1000000).entries.isEmpty())
    }
    @Test fun publicFallbackNeverAcceptsPrivateBackupPaths() {
        assertEquals(2,CloudTransport.urls("speed.json").size)
        try { CloudTransport.urls("vless-card-backup.txt");fail() } catch (_: IllegalArgumentException) { }
        try { CloudTransport.urls("https://evil.example/");fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun recipeBoundsAndCompatibilityProtectReality() {
        fun r(n:Int,d:Int,s:String="")=JSONObject("{\"fragments\":[{\"minLength\":1,\"maxLength\":$n,\"delayMs\":$d,\"sni\":\"$s\"}]}")
        assertEquals(1,CloudControl.fragments(r(100,2)).size)
        assertTrue(CloudControl.fragments(r(1001,2)).isEmpty());assertTrue(CloudControl.fragments(r(10,21)).isEmpty())
        assertTrue(CloudControl.fragments(r(10,2,"evil.example")).isEmpty())
        val mask=CloudControl.fragments(r(10,2,"vk.com")).single()
        assertFalse(com.vlesscardvpn.xray.Masks.compatible(mask,LinkParser.parse(link)!!))
    }
    @Test fun observationRoundTripIsNetworkAndMaskScoped() {
        val e=RouteEvidence().observe(true,100,1000).observe(false,0,2000)
        val s=ServerState(netEvidence=mapOf("wifi|" to e))
        val restored=ServerState.fromJson(s.toJson());assertEquals(2,restored.evidenceFor("wifi").checks);assertEquals(0,restored.evidenceFor("mobile").checks)
        assertEquals(1,e.streak);assertEquals(0,e.observe(true,120,3000).streak)
    }
    @Test fun currentExperienceOverridesCloudButOldExperienceDecays() {
        var e=RouteEvidence();repeat(5) { e=e.observe(true,10,1000000) }
        assertTrue(ServerChoice.blended(.1,e,1000001)>.75)
        assertTrue(ServerChoice.blended(.1,e,1000000+10*86400000L)<.2)
        assertEquals(.8,ServerChoice.blended(.8,RouteEvidence(),1000),.001)
    }
    @Test fun progressiveCooldownMatchesTwoFifteenAndOneHundredTwentyMinutes() {
        assertEquals(120000L,ProbeMemory.cooldownMs(1));assertEquals(900000L,ProbeMemory.cooldownMs(2));assertEquals(7200000L,ProbeMemory.cooldownMs(3))
        val m=ProbeMemory();m.record("n","s","mask",false,1000);m.record("n","s","mask",false,2000)
        assertTrue(m.cooling("n","s","mask",800000));assertFalse(m.cooling("other","s","mask",800000))
        m.record("n","s","mask",true,800001);assertFalse(m.cooling("n","s","mask",800002))
    }
    @Test fun networkKeyDoesNotPersistRawIdentifiers() {
        val a=Net.fingerprint("local-a","Home WiFi","192.168.1.1");val b=Net.fingerprint("local-b","Home WiFi","192.168.1.1")
        assertEquals(16,a.length);assertNotEquals(a,b);assertFalse(a.contains("WiFi"))
    }
    @Test fun staggeredRaceDoesNotStartLaterPairsAfterFirstSuccess()=runBlocking {
        val called=java.util.concurrent.ConcurrentLinkedQueue<Int>()
        ProbeRace.run((0..5).toList(),6,1,staggerMs=300,probe={ i -> called.add(i);delay(5);Probe(50,null,null) },onEach={ _,_-> })
        assertTrue(called.all { it<2 })
    }
    @Test fun refusedTcpDoesNotCancelOtherFilterResults()=runBlocking {
        val listener=java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val closed=java.net.ServerSocket(0);val port=closed.localPort;closed.close()
        val a=Server("a","vless","127.0.0.1",listener.localPort,"a");val b=Server("b","vless","127.0.0.1",port,"b")
        try { val result=Tester.fastFilter(listOf(a,b));assertTrue(result.getValue(a.id)>0);assertEquals(0,result.getValue(b.id)) } finally { listener.close() }
    }
}
