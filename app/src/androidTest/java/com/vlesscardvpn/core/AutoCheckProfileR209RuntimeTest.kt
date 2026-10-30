package com.vlesscardvpn.core
import org.junit.Test
import org.junit.Assert.*
import com.vlesscardvpn.model.Settings

class AutoCheckProfileR209RuntimeTest {
    private val good=Probe(80,null,true,tgOk=true,tgMs=90,callsOk=true,discordOk=true,stableOk=true)
    @Test fun selection00() {
        val p=AutoCheckProfile.of(listOf())
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection01() {
        val p=AutoCheckProfile.of(listOf("telegram"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection02() {
        val p=AutoCheckProfile.of(listOf("stability"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection03() {
        val p=AutoCheckProfile.of(listOf("telegram","stability"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection04() {
        val p=AutoCheckProfile.of(listOf("voice"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection05() {
        val p=AutoCheckProfile.of(listOf("telegram","voice"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection06() {
        val p=AutoCheckProfile.of(listOf("stability","voice"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection07() {
        val p=AutoCheckProfile.of(listOf("telegram","stability","voice"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection08() {
        val p=AutoCheckProfile.of(listOf("discord"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection09() {
        val p=AutoCheckProfile.of(listOf("telegram","discord"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection10() {
        val p=AutoCheckProfile.of(listOf("stability","discord"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection11() {
        val p=AutoCheckProfile.of(listOf("telegram","stability","discord"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection12() {
        val p=AutoCheckProfile.of(listOf("voice","discord"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection13() {
        val p=AutoCheckProfile.of(listOf("telegram","voice","discord"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection14() {
        val p=AutoCheckProfile.of(listOf("stability","voice","discord"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection15() {
        val p=AutoCheckProfile.of(listOf("telegram","stability","voice","discord"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection16() {
        val p=AutoCheckProfile.of(listOf("youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection17() {
        val p=AutoCheckProfile.of(listOf("telegram","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection18() {
        val p=AutoCheckProfile.of(listOf("stability","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection19() {
        val p=AutoCheckProfile.of(listOf("telegram","stability","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection20() {
        val p=AutoCheckProfile.of(listOf("voice","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection21() {
        val p=AutoCheckProfile.of(listOf("telegram","voice","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection22() {
        val p=AutoCheckProfile.of(listOf("stability","voice","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection23() {
        val p=AutoCheckProfile.of(listOf("telegram","stability","voice","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection24() {
        val p=AutoCheckProfile.of(listOf("discord","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection25() {
        val p=AutoCheckProfile.of(listOf("telegram","discord","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection26() {
        val p=AutoCheckProfile.of(listOf("stability","discord","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection27() {
        val p=AutoCheckProfile.of(listOf("telegram","stability","discord","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection28() {
        val p=AutoCheckProfile.of(listOf("voice","discord","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection29() {
        val p=AutoCheckProfile.of(listOf("telegram","voice","discord","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection30() {
        val p=AutoCheckProfile.of(listOf("stability","voice","discord","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun selection31() {
        val p=AutoCheckProfile.of(listOf("telegram","stability","voice","discord","youtube"))
        assertTrue(p.accepts(good,1000));assertEquals(true,p.verdict(good,1000))
        assertEquals(!p.telegram,p.accepts(good.copy(tgOk=false),1000))
        assertEquals(!p.stability,p.accepts(good.copy(stableOk=false),1000))
        assertEquals(!p.youtube,p.accepts(good.copy(ytOk=false),1000))
        assertEquals(!p.discord,p.accepts(good.copy(discordOk=false),1000))
        assertEquals(!p.voice,p.accepts(good.copy(callsOk=false),1000))
        assertFalse(p.accepts(good.copy(realMs=0),1000))
    }
    @Test fun unknownSelectedDoesNotPass() {
        val p=AutoCheckProfile.of(listOf("discord")); assertFalse(p.accepts(good.copy(discordOk=null),1000));assertNull(p.verdict(good.copy(discordOk=null),1000))
    }
    @Test fun defaultProfileIsExplicit() {assertEquals(setOf("telegram","stability","voice","discord"),AutoCheckProfile.defaults)}
    @Test fun stabilityIncludesTelegram() {val p=AutoCheckProfile.of(listOf("stability"));assertTrue(p.telegram);assertFalse(p.accepts(good.copy(tgOk=null),1000))}
    @Test fun unknownIdsIgnored() {assertEquals(setOf("youtube"),AutoCheckProfile.of(listOf("youtube","private-host","nope")).selected)}
    @Test fun fasterThanLowerLimitIsFine() {assertTrue(AutoCheckProfile.of(emptyList()).accepts(good.copy(realMs=1),800))}
    @Test fun upperLimitEnforced() {assertFalse(AutoCheckProfile.of(listOf("telegram")).accepts(good.copy(tgMs=801),800))}
    @Test fun emptySelectionStillRequiresHttps() {assertTrue(AutoCheckProfile.of(emptyList()).accepts(Probe(12,null,null),1000));assertFalse(AutoCheckProfile.of(emptyList()).accepts(Probe(0,null,null),1000))}
    @Test fun profileKeyCanonicalAndDistinct() {assertEquals(AutoCheckProfile.of(listOf("voice","telegram")).key,AutoCheckProfile.of(listOf("telegram","voice","voice")).key);assertNotEquals(AutoCheckProfile.of(emptyList()).key,AutoCheckProfile.of(listOf("youtube")).key)}
    @Test fun toleranceInvalidatesReserveProof() {val p=AutoCheckProfile.of(listOf("telegram")); val k=AutoChecks.key(p,1000);assertTrue(AutoChecks.fresh(k,p,1000,100,200));assertFalse(AutoChecks.fresh(k,p,800,100,200))}
    @Test fun profileChangeInvalidatesReserveProof() {val p=AutoCheckProfile.of(listOf("telegram"));assertFalse(AutoChecks.fresh(AutoChecks.key(p,1000),AutoCheckProfile.of(listOf("youtube")),1000,100,200))}
    @Test fun oldFutureAndEmptyProofRejected() {val p=AutoCheckProfile.of(emptyList());val k=AutoChecks.key(p,1000);assertFalse(AutoChecks.fresh(k,p,1000,100,300101));assertFalse(AutoChecks.fresh(k,p,1000,100,99));assertFalse(AutoChecks.fresh("",p,1000,100,200))}
    @Test fun settingsRoundTripAndMigration() {
        val old=Settings.fromJson(org.json.JSONObject());assertEquals(AutoCheckProfile.defaults,old.autoChecks)
        val empty=old.copy(autoChecks=emptySet());assertEquals(emptySet<String>(),Settings.fromJson(empty.toJson()).autoChecks)
        val custom=old.copy(autoChecks=setOf("youtube","discord"));assertEquals(custom.autoChecks,Settings.fromJson(custom.toJson()).autoChecks)
    }
}
