package com.vlesscardvpn

import com.vlesscardvpn.model.LinkParser
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConfigTest {
    private val reality = "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&type=tcp#Reality%20NL"
    private val links = listOf(
        reality,
        "vless://uuid-ws@cdn.example.com:443?security=tls&type=ws&host=cdn.example.com&path=%2Fws%3Fed%3D2048&sni=cdn.example.com#WS",
        "vless://uuid-x@5.6.7.8:443?security=reality&type=xhttp&mode=packet-up&path=%2Fx&sni=ya.ru&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=ab#XHTTP",
        "vless://uuid-g@9.9.9.9:8443?security=tls&type=grpc&serviceName=gsvc&sni=g.example.com&alpn=h2#gRPC",
        "trojan://pass%40word@t.example.com:443?sni=t.example.com&type=tcp#Trojan",
        "ss://" + com.vlesscardvpn.model.Base64.encode("chacha20-ietf-poly1305:sspass") + "@10.0.0.1:8388#SS",
        "ss://" + com.vlesscardvpn.model.Base64.encode("aes-256-gcm:p@ss@11.0.0.1:8389") + "#SS-old",
        "hysteria2://auth123@h.example.com:8443?sni=h.example.com&obfs=salamander&obfs-password=ob&insecure=1#Hy2",
        "vmess://" + com.vlesscardvpn.model.Base64.encode("""{"v":"2","ps":"VM","add":"vm.example.com","port":"443","id":"uuid-vm","aid":"0","scy":"auto","net":"ws","type":"none","host":"vm.example.com","path":"/vm","tls":"tls","sni":"vm.example.com"}"""),
    )

    @Test fun parsesAllProtocols() {
        val all = links.map { LinkParser.parse(it) }
        all.forEachIndexed { i, s -> assertNotNull("link $i", s) }
        val r = all[0]!!
        assertEquals("Reality NL", r.name); assertEquals("reality", r.security); assertEquals("xtls-rprx-vision", r.flow)
        assertEquals("1.2.3.4", r.address); assertEquals(443, r.port)
        assertEquals("/ws?ed=2048", all[1]!!.path)
        assertEquals("xhttp", all[2]!!.network); assertEquals("packet-up", all[2]!!.mode)
        assertEquals("pass@word", all[4]!!.secret); assertEquals("tls", all[4]!!.security)
        assertEquals("chacha20-ietf-poly1305", all[5]!!.method); assertEquals("sspass", all[5]!!.secret)
        assertEquals("p@ss", all[6]!!.secret); assertEquals("11.0.0.1", all[6]!!.address)
        assertEquals("ob", all[7]!!.obfsPassword); assertTrue(all[7]!!.insecure)
        assertEquals("vmess", all[8]!!.protocol); assertEquals("/vm", all[8]!!.path)
    }

    @Test fun subscriptionBase64AndDedupe() {
        val body = com.vlesscardvpn.model.Base64.encode((links + links[0]).joinToString("\n"))
        assertEquals(links.size, LinkParser.parseMany(body).size)
        assertEquals(0, LinkParser.parseMany("garbage text").size)
    }

    @Test fun linkRoundTrip() {
        links.mapNotNull { LinkParser.parse(it) }.forEach { s ->
            val back = LinkParser.parse(LinkParser.toLink(s))
            assertEquals(s.copy(source = ""), back?.copy(source = ""))
        }
    }

    @Test fun maskCatalog() {
        assertEquals(315, Masks.ALL.size)
        assertEquals(147, Masks.ALL.count { it.viaByeDpi && it.dpi != Masks.CURRENT_DPI })
        val r = LinkParser.parse(reality)!!
        val ws = LinkParser.parse(links[1])!!
        val m = listOf(r to Masks.byId("chrome.d:BYEDPI#VCARD_CASCADE"), ws to Masks.byId("firefox.d:TPWS#VCARD_SHRED"), ws to Masks.byId("edge.h2.b"))
        val c = XrayConfigBuilder.vpnConfig(m, Settings(), 1080, dpiPorts = mapOf("BYEDPI#VCARD_CASCADE" to 1101))
        assertTrue(c.contains("\"dialerProxy\": \"dpi-BYEDPI_VCARD_CASCADE\""))
        assertTrue(c.contains("\"tag\": \"dpi-BYEDPI_VCARD_CASCADE\""))
        // zapret engine not running → that mask falls back to the current strategy
        assertEquals(2, Regex("\"dialerProxy\": \"byedpi\"").findAll(c).count())
        dump("vpn-fixed-dpi", c)
        assertEquals(Masks.ALL.size, Masks.ALL.map { it.id }.toSet().size)
        assertEquals(Masks.ALL.size, Masks.searchOrder(true).size)
        assertTrue(Masks.searchOrder(false).none { it.viaByeDpi })
    }

    @Test fun vpnConfigBalancerAndMasks() {
        val servers = links.mapNotNull { LinkParser.parse(it) }
        val masked = servers.mapIndexed { i, s -> s to Masks.ALL[(i * 37) % Masks.ALL.size] }
        val c = JSONObject(XrayConfigBuilder.vpnConfig(masked, Settings(blockAds = true), 1080))
        val inbound = c.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(10808, inbound.getInt("port")); assertEquals("127.0.0.1", inbound.getString("listen"))
        val b = c.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0)
        assertEquals("leastPing", b.getJSONObject("strategy").getString("type"))
        assertTrue(c.has("observatory"))
        val outs = c.getJSONArray("outbounds")
        assertEquals("proxy-0", outs.getJSONObject(0).getString("tag"))
        dump("vpn-all", c.toString())
        dump("vpn-single", XrayConfigBuilder.vpnConfig(listOf(servers[0] to Masks.byId("firefox.h4.b")), Settings(), 1080))
        dump("vpn-byedpi", XrayConfigBuilder.vpnConfig(emptyList(), Settings(mode = Mode.BYEDPI), 1080))
        dump("vpn-hybrid", XrayConfigBuilder.vpnConfig(listOf(servers[0] to null, servers[1] to null), Settings(mode = Mode.HYBRID, balance = com.vlesscardvpn.model.Balance.LEAST_LOAD), 1080))
        val variants = Masks.ALL.map { servers[0] to it } + servers.map { it to null }
        dump("test", XrayConfigBuilder.testConfig(variants, variants.indices.map { 20000 + it }, 1080))
    }

    @Test fun stealthSocksMuxDnsAndLeaks() {
        val servers = links.mapNotNull { LinkParser.parse(it) }
        val st = Settings(mux = true, blockStun = true, ruDirect = true, ruDns = true, mode = Mode.HYBRID)
        val auth = com.vlesscardvpn.xray.SocksAuth(34567, "u1", "p1")
        val c = JSONObject(XrayConfigBuilder.vpnConfig(servers.map { it to null }, st, 1080, auth))
        val inb = c.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(34567, inb.getInt("port"))
        val ins = inb.getJSONObject("settings")
        assertEquals("password", ins.getString("auth"))
        assertEquals("u1", ins.getJSONArray("accounts").getJSONObject(0).getString("user"))
        val outs = c.getJSONArray("outbounds")
        val byTag = (0 until outs.length()).map { outs.getJSONObject(it) }
        // Vision (reality) and XHTTP must not get mux; WS/gRPC/Trojan do.
        assertFalse(byTag[0].has("mux")); assertTrue(byTag[1].has("mux")); assertFalse(byTag[2].has("mux"))
        val dns = c.getJSONObject("dns").getJSONArray("servers")
        assertEquals("77.88.8.8", dns.getJSONObject(0).getString("address"))
        val rules = c.getJSONObject("routing").getJSONArray("rules").toString()
        assertTrue(rules.contains("3478,5349,19302-19309"))
        assertTrue(rules.contains("\"port\":\"443\""))
        dump("vpn-stealth", c.toString())
        dump("vpn-byedpi-auth", XrayConfigBuilder.vpnConfig(emptyList(), Settings(mode = Mode.BYEDPI), 1080, auth))
    }

    @Test fun dpiStrategies() {
        val all = com.vlesscardvpn.core.DpiStrategies.BUILT_IN
        assertEquals(21, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(5, all.count { it.own })
        all.forEach { s ->
            val argv = s.argv(23456, "vk.com")
            assertFalse(s.id, argv.any { "{sni}" in it || "{mask_pool}" in it })
            if (s.engine == com.vlesscardvpn.core.DpiEngine.TPWS) assertTrue(argv.contains("--bind-addr=127.0.0.1"))
            else assertEquals(listOf("--ip", "127.0.0.1", "--port", "23456"), argv.take(4))
        }
        val cascade = com.vlesscardvpn.core.DpiStrategies.CASCADE.argv(23456, "vk.com")
        assertTrue(cascade.windowed(2).contains(listOf("--fake-sni", "vk.com")))
        assertTrue(cascade.windowed(2).contains(listOf("--fake-sni", "gosuslugi.ru")))
        val net = "Моб.: Beeline"
        val s0 = Settings()
        assertEquals(com.vlesscardvpn.core.DpiStrategies.CUSTOM_ID, com.vlesscardvpn.core.DpiStrategies.resolve(s0, net).id)
        val s1 = s0.copy(dpiRemembered = mapOf(net to "TPWS#SPLIT_DISORDER", "*" to "BYEDPI#DISORDER"))
        assertEquals("TPWS#SPLIT_DISORDER", com.vlesscardvpn.core.DpiStrategies.resolve(s1, net).id)
        assertEquals("BYEDPI#DISORDER", com.vlesscardvpn.core.DpiStrategies.resolve(s1, "Wi-Fi").id)
        assertEquals("BYEDPI#VCARD_SHRED", com.vlesscardvpn.core.DpiStrategies.resolve(s1.copy(dpiStrategy = "BYEDPI#VCARD_SHRED"), net).id)
        val plan = com.vlesscardvpn.core.DpiStrategies.plan(s1, net, tpwsAvailable = false)
        assertTrue(plan.none { it.engine == com.vlesscardvpn.core.DpiEngine.TPWS })
        assertEquals(com.vlesscardvpn.core.DpiStrategies.CUSTOM_ID, plan.first().id)
        val rt = Settings.fromJson(s1.copy(apps = listOf("ru.sberbankmobile"), stealthSocks = false, disguise = "calc").toJson())
        assertEquals(s1.dpiRemembered, rt.dpiRemembered); assertEquals(listOf("ru.sberbankmobile"), rt.apps)
        assertFalse(rt.stealthSocks); assertEquals("calc", rt.disguise)
    }

    @Test fun fragmentMaskGoesToFinalmask() {
        val s = LinkParser.parse(reality)!!
        val o = XrayConfigBuilder.outbound(s, "p", Masks.byId("safari.p3.b"))
        val st = o.getJSONObject("streamSettings")
        assertEquals("safari", st.getJSONObject("realitySettings").getString("fingerprint"))
        assertEquals("fragment", st.getJSONObject("finalmask").getJSONArray("tcp").getJSONObject(0).getString("type"))
        assertEquals("byedpi", st.getJSONObject("sockopt").getString("dialerProxy"))
        val plain = XrayConfigBuilder.outbound(s, "p", null).getJSONObject("streamSettings")
        assertFalse(plain.has("finalmask")); assertEquals("chrome", plain.getJSONObject("realitySettings").getString("fingerprint"))
    }

    /** Local e2e: XRAY_LOCAL_LINKS=file with links → every link × every mask on ports 30000+ (checked by tools/xray-local-e2e.sh). */
    @Test fun dumpLocalMatrix() {
        val f = System.getenv("XRAY_LOCAL_LINKS") ?: return
        val servers = File(f).readLines().mapNotNull { LinkParser.parse(it) }
        val variants = servers.flatMap { s -> (listOf<com.vlesscardvpn.xray.Mask?>(null) + Masks.ALL).map { s to it } }
        // Fixed DPI strategies in front of the server: one host engine per strategy on 1100+i (tools/masks-matrix-ci.sh starts them).
        val dpiPorts = com.vlesscardvpn.core.DpiStrategies.BUILT_IN.mapIndexed { i, st -> st.id to 1100 + i }.toMap()
        File(System.getenv("XRAY_CONFIG_DUMP"), "dpi-engines.txt").writeText(com.vlesscardvpn.core.DpiStrategies.BUILT_IN.mapIndexed { i, st ->
            "${st.engine.name} " + st.argv(1100 + i, "ya.ru").joinToString(" ")
        }.joinToString("\n"))
        dump("local-matrix", XrayConfigBuilder.testConfig(variants, variants.indices.map { 30000 + it }, 1080, dpiPorts))
        File(System.getenv("XRAY_CONFIG_DUMP"), "local-matrix.txt").writeText(variants.mapIndexed { i, (s, m) -> "${30000 + i} ${s.name} ${m?.id ?: "none"}" }.joinToString("\n"))
    }

    private fun dump(name: String, json: String) {
        val dir = System.getenv("XRAY_CONFIG_DUMP") ?: return
        File(dir).mkdirs(); File(dir, "$name.json").writeText(json)
    }
}
