package com.vlesscardvpn.tor

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class TorBridgeRefreshR163Test {
    private val now = 100_000_000L
    private val bridge = "obfs4 192.0.2.1:443 0123456789ABCDEF0123456789ABCDEF01234567 cert=" +
        Base64.getEncoder().withoutPadding().encodeToString(ByteArray(52)) + " iat-mode=0"
    private val catalog get() = TorBridgeCatalog.parse(bridge)
    private fun replace(enabled: Boolean = true, mode: String = "obfs4", lines: String = "old", ticket: Long = 1) =
        TorBridgeRefreshPolicy.replacement(catalog, "obfs4", "old", mode, lines, enabled, 1, ticket)

    @Test fun firstEnabledRefreshIsDue() { assertTrue(TorBridgeRefreshPolicy.due(true,"obfs4",now,0,0)) }
    @Test fun disabledAndPlainOrUnsupportedTransportsNeverAutoFetch() {
        assertFalse(TorBridgeRefreshPolicy.due(false,"obfs4",now,0,0))
        for (mode in listOf("none","snowflake","meek","")) assertFalse(TorBridgeRefreshPolicy.due(true,mode,now,0,0))
    }
    @Test fun successfulRefreshWaitsSixHoursAcrossReleaseAndRestart() {
        val at = now - TorBridgeRefreshPolicy.REFRESH_MS
        assertFalse(TorBridgeRefreshPolicy.due(true,"webtunnel",now,at+1,at+1))
        assertTrue(TorBridgeRefreshPolicy.due(true,"webtunnel",now,at,at))
    }
    @Test fun failuresBackOffForFifteenMinutes() {
        val at = now - TorBridgeRefreshPolicy.RETRY_MS
        assertFalse(TorBridgeRefreshPolicy.due(true,"obfs4",now,0,at+1))
        assertTrue(TorBridgeRefreshPolicy.due(true,"obfs4",now,0,at))
    }
    @Test fun clockRollbackDoesNotDisableUpdatesForever() { assertTrue(TorBridgeRefreshPolicy.due(true,"obfs4",now,now+1,now+1)) }
    @Test fun invalidClockDoesNotStartRequests() { assertFalse(TorBridgeRefreshPolicy.due(true,"obfs4",0,0,0)) }
    @Test fun validCatalogAppliedOnlyToSameUneditedTransport() { assertEquals("Bridge $bridge",replace()) }
    @Test fun manualEditsDuringDownloadArePreserved() { assertNull(replace(lines="user edited")) }
    @Test fun transportSwitchDuringDownloadIsPreserved() { assertNull(replace(mode="webtunnel")) }
    @Test fun disablingAutoDuringDownloadPreventsLateApply() { assertNull(replace(enabled=false)) }
    @Test fun togglingOffAndOnInvalidatesOldRequest() { assertNull(replace(ticket=3)) }
    @Test fun emptyOrWrongTransportCatalogNeverErasesSavedBridges() {
        assertNull(TorBridgeRefreshPolicy.replacement(TorBridgeCatalog.parse("# empty"),"obfs4","old","obfs4","old",true,1,1))
        assertNull(TorBridgeRefreshPolicy.replacement(catalog,"webtunnel","old","webtunnel","old",true,1,1))
    }
}
