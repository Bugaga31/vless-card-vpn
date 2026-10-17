package com.vlesscardvpn
import com.vlesscardvpn.core.CloudSpeed
import org.junit.Assert.*
import org.junit.Test
class CloudFreshSpeedTest {
    private val json="""{"stamp":10000,"isolated":true,"kbps":{"a":9000,"b":10000,"c":30000},"measured_at":{"a":10000,"b":1,"c":10001}}"""
    @Test fun onlyFreshIsolatedDatedHintsAreUsed() {
        assertEquals(mapOf("a" to 9000),CloudSpeed.parseFresh(json,10000000))
    }
    @Test fun legacyParallelRatingsAreNotCertified() {
        assertTrue(CloudSpeed.parseFresh(json.replace("true","false"),10000000).isEmpty())
        assertTrue(CloudSpeed.parseFresh("""{"stamp":10000,"kbps":{"a":9000}}""",10000000).isEmpty())
    }
    @Test fun expiredFutureAndMalformedCloudAreRejected() {
        assertTrue(CloudSpeed.parseFresh(json,14000000).isEmpty())
        assertTrue(CloudSpeed.parseFresh(json,1000).isEmpty())
        assertTrue(CloudSpeed.parseFresh("bad",10000000).isEmpty())
    }
}
