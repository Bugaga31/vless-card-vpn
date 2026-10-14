package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.core.Report
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLoadRegressionR121Test {
    private fun srv(i: Int) = Server(name = "s$i", protocol = "vless", address = "10.0.0.$i", port = 443, secret = "u")

    @Test fun phonesSpreadOverNearEqualServers() {
        Report.load = emptyMap()
        val st = ServerState(realMs = 200, bigOk = true)
        val servers = (1..6).map { srv(it) }
        // 40 phones, 6 servers with the same measurement: the favourite must differ between phones
        val picks = (1..40).map { p -> servers.minByOrNull { Actions.spreadScore(st, it, "phone$p") }!!.id }.toSet()
        assertTrue("phones all chose one server", picks.size >= 3)
        // a clearly faster server still wins on every phone (taste is at most +20%)
        val fast = ServerState(realMs = 100, bigOk = true)
        (1..20).forEach { p -> assertTrue(Actions.spreadScore(fast, servers[0], "p$p") < Actions.spreadScore(st, servers[1], "p$p")) }
    }

    @Test fun cloudLoadPushesPhonesAway() {
        val a = srv(1); val b = srv(2)
        Report.load = mapOf(Report.serverKey(a) to 4)
        val st = ServerState(realMs = 200, bigOk = true)
        assertTrue(Actions.spreadScore(st, a, "x") > Actions.spreadScore(st, b, "x"))
        // my own connection is not counted against me
        val mine = st.copy(selected = true)
        assertEquals(Actions.spreadScore(st.copy(selected = true), a, "x"), Actions.spreadScore(mine, a, "x"))
        assertEquals(8, Report.serverKey(a).length)
        Report.load = emptyMap()
    }
}
