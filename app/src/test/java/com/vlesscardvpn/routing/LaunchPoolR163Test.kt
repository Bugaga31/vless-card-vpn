package com.vlesscardvpn.routing
import com.vlesscardvpn.core.AutoEligibility
import com.vlesscardvpn.model.Server
import org.junit.Assert.*
import org.junit.Test
class LaunchPoolR163Test {
    private val pool = (1..8).map { Server("route-$it", "vless", "192.0.2.$it", 443, "fixture-$it", sni="same.test") }
    @Test fun `manual selection keeps separately masked routes on same host`() { assertEquals(8, AutoEligibility.launchPool(pool,false).size) }
    @Test fun `Auto keeps subnet and SNI diversity guard`() { assertEquals(1, AutoEligibility.launchPool(pool,true).size) }
    @Test fun `manual duplicate entries still collapse by ID`() { assertEquals(pool,AutoEligibility.launchPool(pool+pool,false)) }
}
