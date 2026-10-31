package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test

class AutoProfileR223Test {
    @Test fun instagramUnknownCannotPassSelectedProfile() {
        val profile=AutoCheckProfile.of(setOf("instagram"));val p=Probe(80,null,null)
        assertTrue(profile.instagram);assertFalse(profile.accepts(p,800));assertNull(profile.verdict(p,800))
        assertTrue(profile.accepts(p.copy(instagramOk=true),800));assertEquals(false,profile.verdict(p.copy(instagramOk=false),800))
    }
    @Test fun unselectedInstagramDoesNotRejectGoodHttps() {assertTrue(AutoCheckProfile.of(emptySet()).accepts(Probe(80,null,null,instagramOk=false),800))}
    @Test fun oldServiceKeysCannotRenewNewEvidence() {
        for(id in listOf("youtube","voice","stability","instagram")) {
            val p=AutoCheckProfile.of(setOf(id));assertFalse(AutoChecks.fresh(p.key+"@800",p,800,100,101))
            assertTrue(AutoChecks.fresh(AutoChecks.key(p,800),p,800,100,101))
        }
    }
    @Test fun instagramUnknownCannotBeBypassedByLatencyConsent() {
        val p=Probe(900,null,null,callsOk=false,latencyOnlyOk=true)
        assertFalse(AutoCheckProfile.of(setOf("instagram","voice")).accepts(p.latencyConsented(),Int.MAX_VALUE))
    }
    @Test fun defaultsDoNotSilentlyEnableNewWebService() {assertFalse("instagram" in AutoCheckProfile.defaults)}
}
