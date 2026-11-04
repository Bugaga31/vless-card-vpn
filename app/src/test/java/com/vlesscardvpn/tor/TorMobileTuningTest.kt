package com.vlesscardvpn.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Embedded Tor on a phone: rare flash writes and never a dormant start, for every transport. */
class TorMobileTuningTest {
    @Test fun everyEmbeddedTransportGetsMobileTuning() {
        for (mode in TorCard.EMBEDDED_BRIDGES) {
            val lines = if (mode == "none") emptyList() else listOf("Bridge $mode 192.0.2.1:443 0123456789ABCDEF0123456789ABCDEF01234567")
            val rc = TorCard.torrcEmbedded("/d", "/c", "/n", bridge = mode, bridgeLines = lines).lines()
            assertEquals(mode, 1, rc.count { it == "AvoidDiskWrites 1" })
            assertEquals(mode, 1, rc.count { it == "DormantCanceledByStartup 1" })
            // Tuning never displaces the upstream: Tor still reaches the network only through VLESS.
            assertTrue(mode, rc.indexOf("DormantCanceledByStartup 1") < rc.indexOf(rc.first { it.startsWith("HTTPSProxy 127.0.0.1:") }))
        }
    }
}
