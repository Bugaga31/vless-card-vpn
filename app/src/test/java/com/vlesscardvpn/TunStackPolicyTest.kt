package com.vlesscardvpn

import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TunStackPolicyTest {
    private val node = VlessConfig(name = "Policy fixture", address = "fixture.test", port = 443,
        uuid = "00000000-0000-4000-8000-000000000001", security = "tls", sni = "fixture.test", flow = "")
    private fun config(sdk: Int? = null) = JSONObject(SingBoxManager.generateConfig(null, node, AppSettings(), platformSdk = sdk))
    private fun stack(json: JSONObject): String {
        val rows = json.getJSONArray("inbounds")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("tag") == "tun-in" }.getString("stack")
    }
    @Test fun onlyExecutedLegacyApiGetsWorkaround() {
        assertEquals("gvisor", TunStackPolicy.forSdk(26))
        for (sdk in listOf(null, 25, 27, 28, 33, 34, 35, 36)) assertEquals("mixed", TunStackPolicy.forSdk(sdk))
    }
    @Test fun api26GeneratedConfigurationUsesGvisor() { assertEquals("gvisor", stack(config(26))) }
    @Test fun android13KeepsMixed() { assertEquals("mixed", stack(config(33))) }
    @Test fun unknownOfflinePlatformPreservesPriorDefault() { assertEquals("mixed", stack(config())) }
    @Test fun workaroundDoesNotChangeTlsDnsRoutingOrCredentials() {
        val legacy = config(26); val current = config(33)
        for (field in listOf("outbounds", "dns", "route")) assertEquals(current.get(field).toString(), legacy.get(field).toString())
        val a = legacy.getJSONArray("inbounds"); val b = current.getJSONArray("inbounds")
        assertEquals(b.length(), a.length())
        for (i in 0 until a.length()) {
            val first = JSONObject(a.getJSONObject(i).toString()); val second = JSONObject(b.getJSONObject(i).toString())
            if (first.optString("tag") == "tun-in") { first.remove("stack"); second.remove("stack") }
            assertEquals(second.toString(), first.toString())
        }
    }
}
