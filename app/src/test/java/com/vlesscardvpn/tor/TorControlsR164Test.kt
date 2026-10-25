package com.vlesscardvpn.tor

import com.vlesscardvpn.core.PowerPolicy
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic policy/control tests. They do NOT prove bootstrap, a new IP or carrier access. */
class TorControlsR164Test {
    private class Control : TorControl("127.0.0.1", 1) {
        val countries = mutableListOf<List<String>>()
        var resets = 0; var requests = 0; var fail = false
        override fun connect(cookieHex: String) {}
        override fun close() {}
        override fun setExitCountries(countries: List<String>) { this.countries += countries }
        override fun clearExitCountries() { resets++ }
        override fun signalNewNym() { requests++; if (fail) throw java.io.IOException("fixture failure") }
    }
    @Test fun `new settings and TorAll survive profile serialization`() {
        val s = Settings(torAll=true, torRotation=true, torRotationSeconds=90, torFastOnly=false, torMaxLatencyMs=3500)
        val round = Settings.fromJson(s.toJson())
        assertTrue(round.torAll); assertTrue(round.torRotation)
        assertEquals(90,round.torRotationSeconds); assertFalse(round.torFastOnly); assertEquals(3500,round.torMaxLatencyMs)
    }
    @Test fun `old profiles keep rotation disabled and conservative defaults`() {
        val s = Settings.fromJson(JSONObject())
        assertFalse(s.torAll); assertFalse(s.torRotation); assertEquals(600,s.torRotationSeconds)
        assertTrue(s.torFastOnly); assertEquals(2500,s.torMaxLatencyMs)
    }
    @Test fun `import clamps intervals and thresholds`() {
        val lo = Settings.fromJson(JSONObject().put("torRotationSeconds",-1).put("torMaxLatencyMs",0))
        val hi = Settings.fromJson(JSONObject().put("torRotationSeconds",Int.MAX_VALUE).put("torMaxLatencyMs",Int.MAX_VALUE))
        assertEquals(30,lo.torRotationSeconds); assertEquals(1,lo.torMaxLatencyMs)
        assertEquals(86400,hi.torRotationSeconds); assertEquals(Int.MAX_VALUE,hi.torMaxLatencyMs)
    }
    @Test fun `export also clamps direct invalid copies`() {
        val j=Settings(torRotationSeconds=0,torMaxLatencyMs=0).toJson()
        assertEquals(30,j.getInt("torRotationSeconds")); assertEquals(1,j.getInt("torMaxLatencyMs"))
    }
    @Test fun `positive custom latency survives save and import without hidden clamp`() {
        listOf(1, 99, 100, 249, 250, 15000, 15001, 60000, Int.MAX_VALUE).forEach { value ->
            val round = Settings.fromJson(Settings(torMaxLatencyMs=value).toJson())
            assertEquals(value, round.torMaxLatencyMs)
        }
    }
    @Test fun `one hundred ms is the actual direct Tor measurement ceiling`() {
        val r=TorConnectionPolicy.rotation(Settings(torMaxLatencyMs=100)){0L}
        assertTrue(r.record("nl",100)); assertFalse(r.record("de",101))
        assertTrue(r.isBlacklisted("de")); assertEquals("nl",r.pick())
    }
    @Test fun `custom large latency is not silently reduced to old maximum`() {
        val r=TorConnectionPolicy.rotation(Settings(torMaxLatencyMs=60000)){0L}
        assertTrue(r.record("nl",16000)); assertFalse(r.record("de",60001))
    }
    @Test fun `small threshold with no accepted measurements never fabricates a winner`() {
        val r=TorConnectionPolicy.rotation(Settings(torMaxLatencyMs=100)){0L}
        assertFalse(r.record("nl",101)); assertNull(r.pick())
    }
    @Test fun `Snowflake keeps documented double budget without integer overflow`() {
        val r=TorConnectionPolicy.rotation(Settings(torMaxLatencyMs=100)){0L}
        assertTrue(r.record("nl",200,TorRotation.Transport.SNOWFLAKE))
        assertFalse(r.record("de",201,TorRotation.Transport.SNOWFLAKE))
        val large=TorConnectionPolicy.rotation(Settings(torMaxLatencyMs=Int.MAX_VALUE)){0L}
        assertTrue(large.record("nl",Int.MAX_VALUE.toLong()*2,TorRotation.Transport.SNOWFLAKE))
    }
    @Test fun `invalid negative threshold is normalized on both persistence and runtime`() {
        assertEquals(1,Settings.fromJson(Settings(torMaxLatencyMs=-1).toJson()).torMaxLatencyMs)
        val r=TorConnectionPolicy.rotation(Settings(torMaxLatencyMs=-1)){0L}
        assertTrue(r.record("nl",1)); assertFalse(r.record("de",2))
    }
    @Test fun `home Tor does not erase mode masks bridges or user exceptions`() {
        val original=Settings(mode=Mode.AUTO,torBridge="obfs4",torBridgeLines="user value",myMasks=listOf("user mask"),alwaysDirect=listOf("example.test"))
        val picked=TorConnectionPolicy.selectTor(original)
        assertTrue(picked.torAll); assertTrue(picked.torDirect); assertFalse(TorConnectionPolicy.needsAutoSearch(picked))
        assertEquals(original.myMasks,picked.myMasks); assertEquals(original.torBridgeLines,picked.torBridgeLines)
        assertEquals(original.alwaysDirect,picked.alwaysDirect)
    }
    @Test fun `Tor runtime does not depend on a VLESS catalogue`() {
        val s=TorConnectionPolicy.runtime(Settings(mode=Mode.AUTO,torAll=true))
        assertEquals(Mode.BYEDPI,s.mode); assertTrue(s.torAll)
    }
    @Test fun `leaving Tor restores a chosen normal connection mode`() {
        val s=TorConnectionPolicy.selectMode(Settings(torAll=true),Mode.SERVERS)
        assertFalse(s.torAll); assertEquals(Mode.SERVERS,s.mode)
    }
    @Test fun `ordinary Auto still searches`() {
        assertTrue(TorConnectionPolicy.needsAutoSearch(Settings()))
        assertFalse(TorConnectionPolicy.needsAutoSearch(Settings(mode=Mode.SERVERS)))
    }
    @Test fun `periodic requests happen at requested deadline even without history`() {
        var t=1000L; val r=TorConnectionPolicy.rotation(Settings(torRotationSeconds=30)){t}
        r.markRotated(); t+=29999; assertFalse(r.shouldRotate()); t++; assertTrue(r.shouldRotate())
        r.markRotated(); assertFalse(r.shouldRotate())
    }
    @Test fun `requested cadence need not wait for a faster alternative`() {
        var t=0L; val r=TorConnectionPolicy.rotation(Settings(torRotationSeconds=60)){t}
        r.record("nl",100); r.notePicked("nl"); r.markRotated(); t=60000
        assertTrue(r.shouldRotate())
    }
    @Test fun `offline time does not trigger requests or blacklist samples`() {
        var t=0L; val r=TorConnectionPolicy.rotation(Settings(torRotationSeconds=30)){t}
        r.markRotated(); r.setOnline(false); t=60000
        assertFalse(r.shouldRotate()); assertFalse(r.record("nl",10000)); assertFalse(r.isBlacklisted("nl"))
        r.setOnline(true); assertTrue(r.shouldRotate())
    }
    @Test fun `fast preference rejects slow measurements`() {
        val r=TorConnectionPolicy.rotation(Settings(torMaxLatencyMs=2500)){0L}
        assertTrue(r.record("nl",200)); assertFalse(r.record("jp",2501)); assertTrue(r.isBlacklisted("jp"))
        repeat(100) { assertEquals("nl",r.pick()) }
    }
    @Test fun `fast preference never explores slower known countries`() {
        val r=TorConnectionPolicy.rotation(Settings()){0L}
        listOf("nl","de","fi","se","us").forEachIndexed { i,c->r.record(c,1000L+i*10) }
        repeat(200) { assertEquals("nl",r.pick()) }
    }
    @Test fun `stale measurements cannot pin a country`() {
        var t=0L; val r=TorConnectionPolicy.rotation(Settings()){t}
        r.record("nl",100); t=900001
        assertNull(r.pick())
    }
    @Test fun `future timestamp cannot be used as current evidence`() {
        var t=1000L; val r=TorConnectionPolicy.rotation(Settings()){t}
        r.record("nl",100); t=0
        assertNull(r.pick())
    }
    @Test fun `new network gets a fresh history instance`() {
        val wifi=TorConnectionPolicy.rotation(Settings()){0L}; wifi.record("nl",100)
        val mobile=TorConnectionPolicy.rotation(Settings()){0L}
        assertEquals("nl",wifi.pick()); assertNull(mobile.pick())
    }
    @Test fun `user enabled light rotation is not disabled merely by mobile metering`() {
        val p=PowerPolicy.evaluate(PowerPolicy.Input(now=1000,battery=80,screen=true,metered=true),PowerPolicy.Options(wifiOnly=true))
        assertFalse(p.allowed); assertTrue(p.allowLight)
    }
    @Test fun `data saver and thermal protection still defer optional rotation`() {
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(now=1000,battery=80,dataSaver=true),PowerPolicy.Options()).allowLight)
        assertFalse(PowerPolicy.evaluate(PowerPolicy.Input(now=1000,battery=80,thermal=3),PowerPolicy.Options()).allowLight)
    }
    @Test fun `network reconnect clears inherited preference using authenticated control`() {
        val c=Control(); val rotator=TorRotator(c,cookieHex="01",clearInheritedCountries=true)
        assertTrue(rotator.connect()); assertEquals(1,c.resets); assertEquals(0,c.requests)
    }
    @Test fun `periodic control requests do not need a speed winner`() {
        var t=0L; val c=Control(); val rotator=TorRotator(c,TorConnectionPolicy.rotation(Settings(torRotationSeconds=30)){t},cookieHex="01")
        assertTrue(rotator.connect()); assertFalse(rotator.tick(t)); t=30000
        assertTrue(rotator.tick(t)); assertEquals(1,c.requests); assertTrue(c.countries.isEmpty())
    }
    @Test fun `known fast country is preferred but not declared a guaranteed fast node`() {
        var t=0L; val c=Control(); val rotator=TorRotator(c,TorConnectionPolicy.rotation(Settings(torRotationSeconds=30)){t},cookieHex="01")
        rotator.connect(); rotator.recordExit(100,"nl"); rotator.recordExit(5000,"jp"); t=30000
        assertTrue(rotator.tick(t)); assertEquals(listOf(listOf("nl")),c.countries)
    }
    @Test fun `expired preference is cleared rather than held forever`() {
        var t=0L; val c=Control(); val rotator=TorRotator(c,TorConnectionPolicy.rotation(Settings(torRotationSeconds=30)){t},cookieHex="01")
        rotator.connect(); rotator.recordExit(100,"nl"); t=30000; rotator.tick(t)
        t=901000; assertTrue(rotator.tick(t)); assertEquals(1,c.resets)
    }
    @Test fun `control failure cannot produce repeated requests each second`() {
        var t=0L; val c=Control(); c.fail=true
        val rotator=TorRotator(c,TorConnectionPolicy.rotation(Settings(torRotationSeconds=30)){t},cookieHex="01")
        rotator.connect(); t=30000; assertFalse(rotator.tick(t)); t=31000; assertFalse(rotator.tick(t))
        assertEquals(1,c.requests); t=60000; assertFalse(rotator.tick(t)); assertEquals(2,c.requests)
    }
    @Test fun `closing control prevents late requests`() {
        var t=0L; val c=Control(); val rotator=TorRotator(c,TorConnectionPolicy.rotation(Settings(torRotationSeconds=30)){t},cookieHex="01")
        rotator.connect(); rotator.close(); t=60000; assertFalse(rotator.tick(t)); assertEquals(0,c.requests)
    }
}
