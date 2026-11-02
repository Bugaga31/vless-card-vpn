package com.vlesscardvpn.core

import org.junit.Assert.*
import org.junit.Test
import com.vlesscardvpn.model.Settings

class SiteChecksR243Test {
    @Test fun canonicalDomainsRejectUrlsIpsAndPrivateNames() {
        assertEquals("example.com",SiteCheckCatalog.domain(" ExAmPlE.com. "))
        assertEquals("xn--e1afmkfd.xn--p1ai",SiteCheckCatalog.domain("пример.рф"))
        listOf("", "http://example.com", "a@b.com", "a.com:443", "a.com/path", "127.0.0.1", "[::1]", "localhost", "router.local", "a..com", "bad name.com").forEach { assertNull(it,SiteCheckCatalog.domain(it)) }
    }
    @Test fun onlySuccessfulHttpsResponseIsPass() {
        listOf(200,204,299).forEach {assertEquals(true,SiteCheckCatalog.response(it))}
        listOf(301,302,403,404,429,451,500).forEach {assertNull(SiteCheckCatalog.response(it))}
    }
    @Test fun everySelectedSiteNeedsEvidence() {
        val profile=AutoCheckProfile.of(listOf("site:github.com","site:example.com"))
        val basic=Probe(30,null,null)
        assertFalse(profile.accepts(basic,1000));assertNull(profile.verdict(basic,1000))
        val one=basic.copy(siteOk=mapOf("github.com" to true))
        assertFalse(profile.accepts(one,1000));assertNull(profile.verdict(one,1000))
        val both=basic.copy(siteOk=mapOf("github.com" to true,"example.com" to true))
        assertTrue(profile.accepts(both,1000));assertEquals(true,profile.verdict(both,1000))
        assertFalse(profile.accepts(both.copy(siteOk=mapOf("github.com" to false,"example.com" to true)),1000))
    }
    @Test fun profileKeyChangesWhenSitesChange() {
        val a=AutoCheckProfile.of(listOf("site:github.com")); val b=AutoCheckProfile.of(listOf("site:example.com"))
        val proof=AutoChecks.key(a,1000)
        assertTrue(AutoChecks.fresh(proof,a,1000,100,101));assertFalse(AutoChecks.fresh(proof,b,1000,100,101))
        assertTrue(a.sites==listOf("github.com"));assertTrue(AutoCheckProfile.of(listOf("site:bad/path")).selected.isEmpty())
    }
    @Test fun settingsRoundTripRetainsSelectedAndUnselectedCustomDomains() {
        val s=Settings(autoChecks=setOf("site:github.com"),customCheckDomains=listOf("example.com"))
        val restored=Settings.fromJson(s.toJson())
        assertEquals(s.autoChecks,restored.autoChecks);assertEquals(s.customCheckDomains,restored.customCheckDomains)
    }
    @Test fun nativeFailureDominatesUnknownWebsite() {
        val p=AutoCheckProfile.of(listOf("telegram","site:github.com"))
        assertEquals(false,p.verdict(Probe(30,null,null,tgOk=false),1000))
    }
}
