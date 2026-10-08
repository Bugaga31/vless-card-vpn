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
        assertEquals(518, Masks.ALL.size)
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
        assertEquals(26, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(10, all.count { it.own })
        all.forEach { s ->
            val argv = s.argv(23456, "vk.com")
            assertFalse(s.id, argv.any { "{sni}" in it || "{mask_pool}" in it })
            if (s.engine == com.vlesscardvpn.core.DpiEngine.TPWS) assertTrue(argv.contains("--bind-addr=127.0.0.1"))
            else if (s.engine == com.vlesscardvpn.core.DpiEngine.XRAY) dump("dpi-" + s.id.replace('#', '_'), com.vlesscardvpn.core.DpiStrategies.xrayConfig(s, 23456))
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

    @Test fun moreConfigFormats() {
        val wg = LinkParser.parse("wireguard://cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5cHJpdmE%3D@162.159.192.1:2408?publickey=bmZ1YmxpY2tleXB1YmxpY2tleXB1YmxpY2tleXB1Ymw%3D&address=172.16.0.2%2F32%2C2606%3A4700%3A110%3A8a36%3A%3A1%2F128&reserved=1%2C2%2C3&mtu=1280#WARP")!!
        assertEquals("wireguard", wg.protocol); assertEquals(2408, wg.port); assertEquals("1,2,3", wg.reserved); assertFalse(wg.isTcpBased)
        assertEquals(wg.copy(source = ""), LinkParser.parse(LinkParser.toLink(wg))?.copy(source = ""))
        val conf = "[Interface]\nPrivateKey = cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5cHJpdmE=\nAddress = 172.16.0.2/32\nMTU = 1280\n\n[Peer]\nPublicKey = bmZ1YmxpY2tleXB1YmxpY2tleXB1YmxpY2tleXB1Ymw=\nAllowedIPs = 0.0.0.0/0\nEndpoint = engage.cloudflareclient.com:2408\n"
        val wc = LinkParser.parseMany(conf).single()
        assertEquals("engage.cloudflareclient.com", wc.address); assertEquals(1280, wc.mtu)
        val sk = LinkParser.parse("socks://" + com.vlesscardvpn.model.Base64.encode("user:pa:ss") + "@5.5.5.5:1080#S")!!
        assertEquals("user", sk.user); assertEquals("pa:ss", sk.secret)
        assertEquals(sk.copy(source = ""), LinkParser.parse(LinkParser.toLink(sk))?.copy(source = ""))
        assertEquals("u2", LinkParser.parse("socks5://u2:p2@6.6.6.6:1081")!!.user)
        val json = """[{"remarks":"NL xhttp","outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"7.7.7.7","port":443,"users":[{"id":"uuid-j","encryption":"none"}]}]},
            "streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{"serverName":"x.example.com"},"xhttpSettings":{"path":"/j"},"sockopt":{"dialerProxy":"fragment"}}},
            {"tag":"fragment","protocol":"freedom"},{"tag":"direct","protocol":"freedom"}]},{"remarks":"DE","outbounds":[{"protocol":"trojan","settings":{"servers":[{"address":"8.8.4.4","port":443,"password":"p"}]},"streamSettings":{"security":"tls"}}]}]"""
        val js = LinkParser.parseMany(json, "sub")
        assertEquals(2, js.size); assertEquals("NL xhttp", js[0].name); assertEquals("7.7.7.7", js[0].address)
        assertFalse(js[0].extra.contains("dialerProxy"))
        val all = listOf(wg, wc, sk) + js
        all.forEach { sv -> Masks.searchOrder(true, sv).forEach { m -> assertTrue("${sv.name} ${m.id}", Masks.compatible(m, sv)) } }
        assertEquals(listOf("chrome.n"), Masks.searchOrder(true, js[0]).map { it.id })
        assertEquals(22, Masks.searchOrder(true, wg).size) // plain + 4 UDP noise + 8 WireGuard noise + port hopping (×9)
        val c = XrayConfigBuilder.vpnConfig(all.map { it to Masks.byId(if (!it.isTcpBased && it.protocol != "xray") "chrome.z2" else "chrome.n") }, Settings(), null)
        assertTrue(c.contains("\"noise\"")); assertTrue(c.contains("\"secretKey\""))
        dump("vpn-formats", c)
        // the tester never runs two tunnels of one WireGuard key at once
        val vs = (Masks.searchOrder(true, wg).map { wg to it } + Masks.searchOrder(true, sk).take(10).map { sk to it })
        val ch = com.vlesscardvpn.core.Tester.chunks(vs, 32)
        assertEquals(vs.indices.toList(), ch.flatten().sorted())
        ch.forEach { c -> assertTrue(c.count { vs[it].first.protocol == "wireguard" } <= 1) }
    }

    @Test fun ladderNoiseServicesAuto() {
        val r = LinkParser.parse(reality)!!
        val f = XrayConfigBuilder.outbound(r, "p", Masks.byId("chrome.l2")).getJSONObject("streamSettings").getJSONObject("finalmask")
            .getJSONArray("tcp").getJSONObject(0).getJSONObject("settings")
        assertEquals(8, f.getJSONArray("lengths").length()); assertEquals(8, f.getJSONArray("delays").length())
        val hy = LinkParser.parse(links[7])!!
        val u = XrayConfigBuilder.outbound(hy, "h", Masks.byId("chrome.z3")).getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("udp")
        assertEquals(listOf("salamander", "noise"), (0 until u.length()).map { u.getJSONObject(it).getString("type") })
        val svc = JSONObject(XrayConfigBuilder.vpnConfig(listOf(r to null), Settings(services = listOf("youtube", "telegram")), null))
        val rules = svc.getJSONObject("routing").getJSONArray("rules")
        val last = rules.getJSONObject(rules.length() - 1)
        assertEquals("direct", last.getString("outboundTag"))
        assertTrue(rules.toString().contains("geosite:youtube")); assertTrue(rules.toString().contains("geoip:telegram"))
        dump("vpn-services", svc.toString())
        val auto = JSONObject(XrayConfigBuilder.vpnConfig(emptyList(), Settings(mode = Mode.AUTO), 1080))
        assertTrue(auto.toString().contains("\"outboundTag\":\"byedpi\""))
        dump("vpn-auto-dpi", auto.toString())
        val st = com.vlesscardvpn.model.ServerState(maskId = "chrome.n", netMasks = mapOf("Моб.: Beeline" to "chrome.l1"))
        assertEquals("chrome.l1", st.maskFor("Моб.: Beeline")); assertEquals("chrome.n", st.maskFor("Wi-Fi"))
        assertEquals(st, com.vlesscardvpn.model.ServerState.fromJson(st.toJson()))
        val rt = Settings.fromJson(Settings(services = listOf("telegram"), autoHeal = false, lastSubRefresh = 5).toJson())
        assertEquals(listOf("telegram"), rt.services); assertFalse(rt.autoHeal); assertEquals(5L, rt.lastSubRefresh); assertEquals(Mode.AUTO, rt.mode)
        assertTrue(com.vlesscardvpn.model.Subs.isWhitelist(com.vlesscardvpn.model.Subs.CATALOG.first { it.group == com.vlesscardvpn.model.Subs.WL }.url))
        assertEquals(3, com.vlesscardvpn.model.Subs.mirrors("https://raw.githubusercontent.com/zieng2/wl/main/vless_lite.txt").size)
    }

    /** Local e2e: XRAY_LOCAL_LINKS=file with links → every link × every mask on ports 30000+ (checked by tools/xray-local-e2e.sh). */
    @Test fun dumpLocalMatrix() {
        val f = System.getenv("XRAY_LOCAL_LINKS") ?: return
        File(System.getenv("XRAY_CONFIG_DUMP")).mkdirs()
        val servers = File(f).readLines().mapNotNull { LinkParser.parse(it) }
        val variants = servers.flatMap { s -> (listOf<com.vlesscardvpn.xray.Mask?>(null) + Masks.ALL).map { s to it } }
        // Fixed DPI strategies in front of the server: one host engine per strategy on 1100+i (tools/masks-matrix-ci.sh starts them).
        val dpiPorts = com.vlesscardvpn.core.DpiStrategies.BUILT_IN.mapIndexed { i, st -> st.id to 1100 + i }.toMap()
            .filterKeys { !it.startsWith("XRAY#") }
        File(System.getenv("XRAY_CONFIG_DUMP"), "dpi-engines.txt").writeText(com.vlesscardvpn.core.DpiStrategies.BUILT_IN.mapIndexed { i, st ->
            if (st.engine == com.vlesscardvpn.core.DpiEngine.XRAY) {
                val f = File(System.getenv("XRAY_CONFIG_DUMP"), "xrdpi-${1100 + i}.json"); f.writeText(com.vlesscardvpn.core.DpiStrategies.xrayConfig(st, 1100 + i))
                "XRAY ${f.path}"
            } else "${st.engine.name} " + st.argv(1100 + i, "ya.ru").joinToString(" ")
        }.joinToString("\n"))
        dump("local-matrix", XrayConfigBuilder.testConfig(variants, variants.indices.map { 30000 + it }, 1080, dpiPorts))
        File(System.getenv("XRAY_CONFIG_DUMP"), "local-matrix.txt").writeText(variants.mapIndexed { i, (s, m) -> "${30000 + i} ${s.name} ${m?.id ?: "none"}" }.joinToString("\n"))
    }

    private fun dump(name: String, json: String) {
        val dir = System.getenv("XRAY_CONFIG_DUMP") ?: return
        File(dir).mkdirs(); File(dir, "$name.json").writeText(json)
    }
}
