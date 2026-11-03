package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualServerCheckPolicyR172Test {
    private val tcp = Server("tcp", "vless", "127.0.0.1", 443, "secret")
    private val udp = Server("udp", "wireguard", "127.0.0.1", 443, "secret")

    @Test
    fun `manual checks stay below device policy limits`() {
        val normal = ManualServerCheckPolicy.limits(PowerPolicy.Plan(tcpParallel = 96, realParallel = 12))
        assertEquals(ManualServerCheckPolicy.Limits(12, 4, 16), normal)

        val pressured = ManualServerCheckPolicy.limits(PowerPolicy.Plan(allowed = false, tcpParallel = 2, realParallel = 2, allowLight = false))
        assertEquals(ManualServerCheckPolicy.Limits(2, 2, 4), pressured)

        val eco = ManualServerCheckPolicy.limits(PowerPolicy.Plan(tcpParallel = 16, realParallel = 2))
        assertEquals(ManualServerCheckPolicy.Limits(4, 2, 4), eco)
    }

    @Test
    fun `unchecked stale and future results are not removable`() {
        val now = 2_000_000L
        assertFalse(ManualServerCheckPolicy.confirmedTcpFailure(tcp, ServerState(), now))
        assertFalse(ManualServerCheckPolicy.confirmedTcpFailure(tcp, ServerState(tcpMs = -1, checkedAt = now), now))
        assertFalse(ManualServerCheckPolicy.confirmedTcpFailure(tcp, ServerState(tcpMs = 0, checkedAt = now - 86_400_001L), now))
        assertFalse(ManualServerCheckPolicy.confirmedTcpFailure(tcp, ServerState(tcpMs = 0, checkedAt = now + 1), now))
        assertFalse(ManualServerCheckPolicy.confirmedTcpFailure(udp, ServerState(tcpMs = 0, checkedAt = now), now))
    }

    @Test
    fun `recent TCP timeout is removable only for TCP-based server`() {
        val now = 2_000_000L
        assertTrue(ManualServerCheckPolicy.confirmedTcpFailure(tcp, ServerState(tcpMs = 0, checkedAt = now), now))
        assertFalse(ManualServerCheckPolicy.confirmedTcpFailure(tcp, ServerState(tcpMs = 12, checkedAt = now), now))
    }
}
