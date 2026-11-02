package com.vlesscardvpn.routing
import com.vlesscardvpn.core.RuntimeXrayConfig
import com.vlesscardvpn.core.AutoEligibility
import com.vlesscardvpn.model.Server
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class DebugFixtureR163Test {
    private fun config(host: String, protocol: String = "vless") = """{"outbounds":[{"protocol":"$protocol","settings":{"vnext":[{"address":"$host","port":8444,"users":[{"id":"fixture-only"}]}]}}]}"""
    @Test fun `exact debug alias maps only network destination and preserves authentication`() {
        val result=JSONObject(RuntimeXrayConfig.prepare(config("r163-pool-0.test"))).getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals(if(com.vlesscardvpn.BuildConfig.DEBUG) "10.0.2.2" else "r163-pool-0.test",result.getString("address")); assertEquals(8444,result.getInt("port"))
        assertEquals("fixture-only",result.getJSONArray("users").getJSONObject(0).getString("id"))
        if(!com.vlesscardvpn.BuildConfig.DEBUG) assertEquals(config("r163-pool-0.test"), RuntimeXrayConfig.prepare(config("r163-pool-0.test")))
    }
    @Test fun `other hosts and protocols are not remapped`() {
        for (host in listOf("example.com","r163-pool-8.test","r163-pool-0.test.attacker.test"))
            assertEquals(JSONObject(config(host)).toString(),JSONObject(RuntimeXrayConfig.prepare(config(host))).toString())
        assertEquals(JSONObject(config("r163-pool-0.test","trojan")).toString(),JSONObject(RuntimeXrayConfig.prepare(config("r163-pool-0.test","trojan"))).toString())
    }
    @Test fun `eight fixture aliases exercise diversity without weakening Auto guard`() {
        val routes=(0..7).map { Server("fixture-$it","vless","r163-pool-$it.test",8444,"fixture-$it",sni="r163-pool-$it.test") }
        assertEquals(8,AutoEligibility.launchPool(routes,true).size)
    }
}
