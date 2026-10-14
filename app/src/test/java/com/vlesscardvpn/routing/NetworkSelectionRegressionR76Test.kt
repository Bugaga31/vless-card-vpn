package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkSelectionRegressionR76Test {
    @Test fun closeOnesKeepsOnlyComparable() {
        assertEquals(2, Actions.closeOnes(listOf(200, 260, 400, 2000)))   // 200·1.25+50 = 300
        assertEquals(1, Actions.closeOnes(listOf(200, 800)))               // slow reserve is not in the live pool
        assertEquals(1, Actions.closeOnes(listOf(200, 5000)))              // hopeless spare is dropped
        assertEquals(1, Actions.closeOnes(listOf(200, 700, 1300, 5000)))   // never force three slow routes
        assertEquals(1, Actions.closeOnes(listOf(300))); assertEquals(0, Actions.closeOnes(emptyList()))
    }

    @Test fun netServersRoundTrip() {
        val s = Settings(netServers = mapOf("Wi-Fi · Home" to listOf("a", "b"), "Моб.: MTS" to listOf("c")))
        assertEquals(s.netServers, Settings.fromJson(s.toJson()).netServers)
    }
}
