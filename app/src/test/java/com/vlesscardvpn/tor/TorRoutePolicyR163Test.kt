package com.vlesscardvpn.tor

import com.vlesscardvpn.model.Settings
import org.junit.Assert.*
import org.junit.Test

class TorRoutePolicyR163Test {
    @Test fun `TorAll cannot prove a selected VLESS server`() {
        assertFalse(TorRoutePolicy.canAttributeServerEvidence(TorRoutePolicy.active(Settings(torAll=true))))
    }
    @Test fun `mixed Tor service route cannot prove a selected VLESS server`() {
        assertFalse(TorRoutePolicy.canAttributeServerEvidence(TorRoutePolicy.active(Settings(torRouting=true, services=listOf("telegram")))))
    }
    @Test fun `inactive Tor preserves server evidence attribution`() {
        assertTrue(TorRoutePolicy.canAttributeServerEvidence(TorRoutePolicy.active(Settings(torRouting=true))))
        assertTrue(TorRoutePolicy.canAttributeServerEvidence(TorRoutePolicy.active(Settings())))
    }
}
