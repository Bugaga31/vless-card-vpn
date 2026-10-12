package com.vlesscardvpn

import com.vlesscardvpn.core.CloudSpeed
import com.vlesscardvpn.core.Report
import com.vlesscardvpn.core.AppState
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R124Test {
    private fun srv(i: Int) = Server(name = "s$i", protocol = "vless", address = "10.0.0.$i", port = 443, secret = "u",
        source = "https://raw.githubusercontent.com/x/y/main/list.txt")

    @Test fun netParseBlockedSpeed() {
        val a = srv(1); val b = srv(2); val c = srv(3)
        val n = CloudSpeed.parseNet("""{"${Report.serverKey(a)}":[8000,90,4],"${Report.serverKey(b)}":[0,0,3],"${Report.serverKey(c)}":[0,0,1]}""")
        assertFalse(CloudSpeed.blocked(a, n)); assertTrue(CloudSpeed.blocked(b, n)); assertFalse(CloudSpeed.blocked(c, n)) // one check is not enough
        // operator speed beats the GitHub one; unknown here → GitHub
        assertEquals(8000, CloudSpeed.speedOf(Report.serverKey(a), mapOf(Report.serverKey(a) to 100000), n))
        assertEquals(50000, CloudSpeed.speedOf(Report.serverKey(srv(4)), mapOf(Report.serverKey(srv(4)) to 50000), n))
        // pick skips blocked even if GitHub saw it fast
        val got = CloudSpeed.pick(listOf(a, b), emptyList(), speeds = mapOf(Report.serverKey(b) to 90000), here = n)
        assertEquals(listOf(a), got)
    }

    @Test fun reportSpeeds() {
        val now = 10_000_000L
        val a = srv(1); val b = srv(2); val c = srv(3); val own = srv(4).copy(source = "my")
        val st = AppState(servers = listOf(a, b, c, own), states = mapOf(
            a.id to ServerState(realMs = 300, tcpMs = 50, kbps = 7000, ytOk = true, checkedAt = now - 1000),
            b.id to ServerState(realMs = 0, tcpMs = 60, checkedAt = now - 1000),
            c.id to ServerState(realMs = 200, tcpMs = 40, kbps = 9000, checkedAt = now - 7 * 3600_000L),  // too old
            own.id to ServerState(realMs = 100, tcpMs = 10, kbps = 50000, checkedAt = now)))               // not public
        val sp = Report.speeds(st, now)
        assertEquals(2, sp.length())
        assertEquals(Report.serverKey(a), sp.getJSONArray(0).getString(0)); assertEquals(7000, sp.getJSONArray(0).getInt(1)); assertEquals(1, sp.getJSONArray(0).getInt(3))
        assertEquals(0, sp.getJSONArray(1).getInt(2))
    }
}
