package com.vlesscardvpn

import com.vlesscardvpn.core.CloudSpeed
import com.vlesscardvpn.core.Report
import com.vlesscardvpn.model.Server
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSpeedRegressionR122Test {
    private fun srv(i: Int) = Server(name = "s$i", protocol = "vless", address = "10.0.0.$i", port = 443, secret = "u")

    @Test fun keyMatchesCloud() {
        // python: hashlib.sha256(b"1.2.3.4:443").hexdigest()[:8]
        assertEquals("156f7e0f", Report.serverKey(Server(name = "x", protocol = "vless", address = "1.2.3.4", port = 443, secret = "u")))
    }

    @Test fun parseSpeed() {
        val m = CloudSpeed.parse("""{"stamp":1,"kbps":{"aa":5000,"bb":0}}""")
        assertEquals(mapOf("aa" to 5000), m)
        assertEquals(emptyMap<String, Int>(), CloudSpeed.parse("oops"))
    }

    @Test fun pickFastNotNear() {
        val all = (1..6).map { srv(it) }
        val sp = mapOf(Report.serverKey(all[0]) to 90000, Report.serverKey(all[3]) to 20000, Report.serverKey(all[4]) to 50000, Report.serverKey(all[5]) to 1000)
        val got = CloudSpeed.pick(all, skip = listOf(all[0]), n = 10, speeds = sp)
        assertEquals(listOf(all[4], all[3]), got)  // near one skipped, slow one (<3 Мбит/с) dropped, fastest first
        assertEquals(listOf(all[4]), CloudSpeed.pick(all, listOf(all[0]), n = 1, speeds = sp))
        assertEquals(emptyList<Server>(), CloudSpeed.pick(all, emptyList(), speeds = emptyMap()))
    }
}
