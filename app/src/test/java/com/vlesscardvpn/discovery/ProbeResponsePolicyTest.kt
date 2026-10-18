package com.vlesscardvpn
import com.vlesscardvpn.core.ProbeResponsePolicy
import org.junit.Assert.*
import org.junit.Test
class ProbeResponsePolicyTest {
    @Test fun generate204RequiresAnActual204NotALoginOrRedirectPage() {
        val url="https://www.gstatic.com/generate_204?probe=1"
        assertTrue(ProbeResponsePolicy.connectivity(204,url))
        for(code in listOf(200,301,302,403,500)) assertFalse(ProbeResponsePolicy.connectivity(code,url))
        assertTrue(ProbeResponsePolicy.connectivity(200,"https://example.org/health"))
        assertFalse(ProbeResponsePolicy.connectivity(500,"https://example.org/health"))
    }
    @Test fun htmlJsonAndXmlResponsesAreNotBandwidthProof() {
        for(type in listOf("text/html", "TEXT/HTML; charset=UTF-8", "application/json", "application/problem+json", "application/xhtml+xml", "text/xml"))
            assertFalse(ProbeResponsePolicy.download(200,type,null))
        assertTrue(ProbeResponsePolicy.download(200,"application/octet-stream",null))
        assertTrue(ProbeResponsePolicy.download(206,"application/octet-stream","identity"))
        assertTrue(ProbeResponsePolicy.download(200,null,null))
    }
    @Test fun compressedAndRedirectResponsesCannotInflateMeasuredBandwidth() {
        for(encoding in listOf("gzip","br","deflate","identity,gzip"))
            assertFalse(ProbeResponsePolicy.download(200,"application/octet-stream",encoding))
        for(code in listOf(204,301,302,403,500)) assertFalse(ProbeResponsePolicy.download(code,"application/octet-stream",null))
    }
}
