package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LatencyMigrationR227Test {
    @Test fun firstRequestDoesNotClipThreeSecondAllowance() { assertEquals(3100L,FirstConnectivityBudget.cap(true,true));assertEquals(0L,FirstConnectivityBudget.cap(false,true)) }
    @Test fun freshInstallUsesWideRange() { assertEquals(3000, Settings().autoLatencyMaxMs) }
    @Test fun previousVersionMigratesOnceAsRequested() {
        val old = Settings(autoLatencyMinMs=50, autoLatencyMaxMs=800).toJson().put("autoLatencyPresetVersion", 213)
        val s = Settings.fromJson(old)
        assertEquals(100, s.autoLatencyMinMs); assertEquals(3000, s.autoLatencyMaxMs)
    }
    @Test fun newVersionPreservesSubsequentUserChoice() {
        val s = Settings.fromJson(Settings(autoLatencyMinMs=50, autoLatencyMaxMs=1200).toJson())
        assertEquals(50, s.autoLatencyMinMs); assertEquals(1200, s.autoLatencyMaxMs)
    }
    @Test fun oldEvidenceKeyCannotPassNewLimit() {
        val p = AutoCheckProfile.of(emptySet())
        assertFalse(AutoChecks.fresh(AutoChecks.key(p,800), p,3000,100,101))
    }
    @Test fun selectedServiceFailureRemainsFailureAt3000() {
        val p = AutoCheckProfile.of(setOf("youtube"))
        assertFalse(p.accepts(Probe(100,null,false),3000))
        assertFalse(p.accepts(Probe(100,null,null),3000))
    }
}
