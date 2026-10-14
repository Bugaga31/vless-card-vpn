package com.vlesscardvpn

import com.vlesscardvpn.core.Actions
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelegramRoutingRegressionR125Test {
    @Test fun telegramFailingServersStayOut() {
        val tg = mapOf("a" to true, "b" to false, "c" to null)
        assertEquals(listOf("a", "c"), Actions.tgCapable(listOf("a", "b", "c")) { tg[it] })
        assertEquals(listOf("b"), Actions.tgCapable(listOf("b")) { tg[it] })  // nothing else: keep it rather than none
    }

    @Test fun hybridNeverSendsTelegramToDpi() {
        val old = Settings(mode = Mode.HYBRID, hybridDomains = listOf("geosite:youtube", "geosite:telegram", "domain:t.me"))
        assertEquals(listOf("geosite:youtube"), XrayConfigBuilder.hybridDomains(old))
        assertFalse("geosite:telegram" in Settings().hybridDomains)
    }
}
