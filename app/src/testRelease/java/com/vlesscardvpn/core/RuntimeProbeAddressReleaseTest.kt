package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
class RuntimeProbeAddressReleaseTest {
    @Test fun productionNeverRewritesEndpointsIncludingFixtureLikeNames() {
        listOf("r163-pool-0.test","example.com","192.0.2.1").forEach {
            assertEquals(it,RuntimeProbeAddress.host(it))
        }
    }
}
