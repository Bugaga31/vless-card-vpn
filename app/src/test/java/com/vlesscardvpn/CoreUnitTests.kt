package com.vlesscardvpn

import com.vlesscardvpn.core.SingBoxManager
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.util.UniversalConfigParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreUnitTests {

    @Test
    fun testVlessUriParsing() {
        val rawUri = "vless://b7a5e840-7e61-460d-a7fa-0dc6b6a6742a@185.196.10.15:443?security=reality&flow=xtls-rprx-vision&sni=yandex.ru&fp=chrome&pbk=Xb2qfC4uJt4Xh3mP9nL8rK1sV5wY0zQ3aE6dG7iO2k=&sid=6a#TestNode"
        val config = UniversalConfigParser.parseSingleUri(rawUri)

        assertNotNull("Config must be parsed successfully", config)
        assertEquals("b7a5e840-7e61-460d-a7fa-0dc6b6a6742a", config?.uuid)
        assertEquals("185.196.10.15", config?.address)
        assertEquals(443, config?.port)
        assertEquals("yandex.ru", config?.sni)
        assertEquals("reality", config?.security)
        assertEquals("xtls-rprx-vision", config?.flow)
        assertEquals("Xb2qfC4uJt4Xh3mP9nL8rK1sV5wY0zQ3aE6dG7iO2k=", config?.publicKey)
        assertEquals("6a", config?.shortId)
        assertEquals("TestNode", config?.name)
    }

    @Test
    fun testVmessUriParsing() {
        val vmessRaw = "vmess://eyJhZGQiOiIxOTQuMzYuMTkxLjEyIiwiYWlkIjowLCJob3N0IjoiIiwiYWRkciI6IjE5NC4zNi4xOTEuMTIiLCJpZCI6ImM4YjRmOTEyLTRkMjMtNDExYS05NmVhLTVmYjNkODgxOTBiYyIsIm5ldCI6InRjcCIsInBhdGgiOiIiLCJwb3J0IjoxMDAwMiwicHMiOiJWbWVzc1Rlc3QiLCJ0bHMiOiIiLCJ0eXBlIjoibm9uZSIsInYiOiIyIn0="
        val config = UniversalConfigParser.parseSingleUri(vmessRaw)

        assertNotNull(config)
        assertEquals("vmess", config?.protocolType)
        assertEquals("c8b4f912-4d23-411a-96ea-5fb3d88190bc", config?.uuid)
        assertEquals("VmessTest", config?.name)
    }

    @Test
    fun testSingBoxConfigStructure() {
        val config = VlessConfig(
            id = "test-1",
            name = "Test VLESS",
            address = "185.196.10.15",
            port = 443,
            uuid = "b7a5e840-7e61-460d-a7fa-0dc6b6a6742a",
            protocolType = "vless",
            flow = "xtls-rprx-vision",
            security = "reality",
            sni = "yandex.ru",
            publicKey = "Xb2qfC4uJt4Xh3mP9nL8rK1sV5wY0zQ3aE6dG7iO2k=",
            shortId = "6a"
        )

        // Mock context not needed for pure JSON construction test
        val fakeConfigJson = SingBoxManager.generateConfig(
            context = android.app.Application(),
            config = config,
            settings = AppSettings(enableRuDirect = true, blockQuicYouTube = true)
        )

        val json = JSONObject(fakeConfigJson)
        assertTrue(json.has("inbounds"))
        assertTrue(json.has("outbounds"))
        assertTrue(json.has("route"))
        assertTrue(json.has("dns"))

        val inbounds = json.getJSONArray("inbounds")
        val tun = inbounds.getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        assertEquals(1400, tun.getInt("mtu"))

        val outbounds = json.getJSONArray("outbounds")
        val proxy = outbounds.getJSONObject(0)
        assertEquals("vless", proxy.getString("type"))
        assertEquals("185.196.10.15", proxy.getString("server"))
        assertEquals(443, proxy.getInt("server_port"))
        assertEquals("yandex.ru", proxy.getJSONObject("tls").getString("server_name"))
        assertTrue(proxy.getJSONObject("tls").has("reality"))
    }
}
