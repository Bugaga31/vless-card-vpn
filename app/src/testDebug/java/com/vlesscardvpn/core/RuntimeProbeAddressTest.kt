package com.vlesscardvpn.core
import org.junit.Assert.*
import org.junit.Test
class RuntimeProbeAddressTest {
    @Test fun exactFixtureAliasesUseEmulatorHost() {
        repeat(8) { assertEquals("10.0.2.2",RuntimeProbeAddress.host("r163-pool-$it.test")) }
    }
    @Test fun unrelatedHostsAreNeverRewritten() {
        listOf("example.com","10.0.2.2","r163-pool-8.test","r163-pool-0.test.evil","R163-pool-0.test").forEach {
            assertEquals(it,RuntimeProbeAddress.host(it))
        }
    }
}
