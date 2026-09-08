package com.vlesscardvpn

import com.vlesscardvpn.core.V2RayManager
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.VlessConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class V2RayManagerTest {

    @Test
    fun testV2RayConfigGenerationVless() {
        val config = VlessConfig(
            id = "test-1",
            name = "Test VLESS",
            address = "1.2.3.4",
            port = 443,
            uuid = "11111111-2222-3333-4444-555555555555",
            protocolType = "vless",
            security = "tls",
            sni = "yandex.ru"
        )
        val jsonStr = V2RayManager.generateConfig(config, 10808)
        val json = JSONObject(jsonStr)

        assertTrue(json.has("inbounds"))
        assertTrue(json.has("outbounds"))

        val inbounds = json.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        assertEquals(10808, inbounds.getJSONObject(0).getInt("port"))

        val outbounds = json.getJSONArray("outbounds")
        val proxyOutbound = outbounds.getJSONObject(0)
        assertEquals("vless", proxyOutbound.getString("protocol"))
        
        val stream = proxyOutbound.getJSONObject("streamSettings")
        assertEquals("tls", stream.getString("security"))
        assertEquals("yandex.ru", stream.getJSONObject("tlsSettings").getString("serverName"))
    }

    @Test
    fun testV2RayConfigGenerationVmessWs() {
        val config = VlessConfig(
            id = "test-2",
            name = "Test VMess WS",
            address = "9.9.9.9",
            port = 8443,
            uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
            protocolType = "vmess",
            security = "tls",
            transport = "ws",
            wsPath = "/graphql",
            wsHost = "vk.com",
            sni = "vk.com"
        )
        val jsonStr = V2RayManager.generateConfig(config, 20808)
        val json = JSONObject(jsonStr)

        val outbounds = json.getJSONArray("outbounds")
        val proxyOutbound = outbounds.getJSONObject(0)
        assertEquals("vmess", proxyOutbound.getString("protocol"))
        
        val stream = proxyOutbound.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("/graphql", stream.getJSONObject("wsSettings").getString("path"))
    }
}
