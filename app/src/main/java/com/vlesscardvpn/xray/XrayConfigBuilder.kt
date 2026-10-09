package com.vlesscardvpn.xray

import com.vlesscardvpn.model.Balance
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import org.json.JSONArray
import org.json.JSONObject

/** Local SOCKS listener of the running VPN (random port + login/password in stealth mode). */
data class SocksAuth(val port: Int, val user: String = "", val pass: String = "") { val auth: Boolean get() = user.isNotEmpty() }

/** Builds Xray-core JSON configs. Pure (no Android APIs) so it is covered by JVM tests and `xray run -test`. */
object XrayConfigBuilder {
    const val BYEDPI_TAG = "byedpi"
    const val PROXY_PREFIX = "proxy-"
    const val RU_DNS = "77.88.8.8"
    val DNS_FALLBACKS = listOf("https://8.8.8.8/dns-query", "https://9.9.9.9/dns-query", "8.8.8.8", "1.1.1.1")
    /** DoH/DoT names resolved without asking anyone (Private DNS and «свой DNS» by name work even when DNS is broken). */
    val DNS_HOSTS = mapOf("dns.google" to "8.8.8.8", "cloudflare-dns.com" to "1.1.1.1", "one.one.one.one" to "1.1.1.1",
        "dns.quad9.net" to "9.9.9.9", "dns.adguard-dns.com" to "94.140.14.14", "common.dot.dns.yandex.net" to "77.88.8.8")
    val RU_DOMAINS = listOf("geosite:category-ru", "domain:ru", "domain:su", "domain:xn--p1ai")
    private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    private fun arr(vararg v: Any): JSONArray = JSONArray().apply { v.forEach { put(it) } }
    private fun csv(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Proxy outbound for [s] with masking [mask] (per outbound, so many masks work at once). */
    fun outbound(s: Server, tag: String, mask: Mask?, mux: Boolean = false): JSONObject {
        if (s.protocol == "xray") return JSONObject(s.extra).put("tag", tag)
        val o = JSONObject().put("tag", tag)
        when (s.protocol) {
            "wireguard" -> o.put("protocol", "wireguard").put("settings", JSONObject().put("secretKey", s.secret)
                .put("address", JSONArray(csv(s.localAddress).ifEmpty { listOf("172.16.0.2/32") }))
                .put("peers", arr(JSONObject().put("publicKey", s.pbk).put("endpoint", (if (':' in s.address) "[${s.address}]" else s.address) + ":${s.port}")
                    .put("keepAlive", 25).apply { if (s.psk.isNotEmpty()) put("preSharedKey", s.psk) }))
                .put("mtu", if (s.mtu > 0) s.mtu else 1280).put("noKernelTun", true)
                .apply { csv(s.reserved).mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }?.let { put("reserved", JSONArray(it)) } })
            "socks" -> o.put("protocol", "socks").put("settings", JSONObject().put("address", s.address).put("port", s.port)
                .apply { if (s.user.isNotEmpty() || s.secret.isNotEmpty()) put("user", s.user).put("pass", s.secret) })
            "vless" -> o.put("protocol", "vless").put("settings", JSONObject().put("vnext", arr(JSONObject()
                .put("address", s.address).put("port", s.port).put("users", arr(JSONObject().put("id", s.secret)
                    .put("encryption", s.encryption.ifEmpty { "none" }).put("level", 0).apply { if (s.flow.isNotEmpty()) put("flow", s.flow) })))))
            "vmess" -> o.put("protocol", "vmess").put("settings", JSONObject().put("vnext", arr(JSONObject()
                .put("address", s.address).put("port", s.port).put("users", arr(JSONObject().put("id", s.secret)
                    .put("security", s.method.ifEmpty { "auto" }).put("level", 0))))))
            "trojan" -> o.put("protocol", "trojan").put("settings", JSONObject().put("servers", arr(JSONObject()
                .put("address", s.address).put("port", s.port).put("password", s.secret).put("level", 0))))
            "shadowsocks" -> o.put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", arr(JSONObject()
                .put("address", s.address).put("port", s.port).put("password", s.secret)
                .put("method", if (s.method == "plain") "none" else s.method).put("level", 0))))
            "hysteria2" -> o.put("protocol", "hysteria").put("settings", JSONObject().put("version", 2)
                .put("address", s.address).put("port", s.port))
            else -> throw IllegalArgumentException("unsupported protocol ${s.protocol}")
        }
        o.put("streamSettings", stream(s, mask))
        if ((mux || mask?.mux == true) && muxable(s)) o.put("mux", JSONObject().put("enabled", true).put("concurrency", 8)
            .put("xudpConcurrency", 16).put("xudpProxyUDP443", "reject"))
        return o
    }

    /** Mux is incompatible with XTLS Vision, XHTTP (has its own) and UDP transports. */
    fun muxable(s: Server): Boolean = s.isTcpBased && s.flow.isEmpty() && s.network != "xhttp" && s.protocol != "hysteria2"

    private fun stream(s: Server, mask: Mask?): JSONObject {
        val st = JSONObject()
        val net = when (s.protocol) { "shadowsocks", "socks" -> "tcp"; "hysteria2" -> "hysteria"; "wireguard" -> ""; else -> s.network }
        if (net.isNotEmpty()) st.put("network", net)
        var hostSni = ""
        when (net) {
            "tcp" -> if (s.headerType == "http") {
                val hosts = csv(s.host)
                st.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", JSONObject()
                    .put("version", "1.1").put("method", "GET").put("path", JSONArray(csv(s.path).ifEmpty { listOf("/") }))
                    .put("headers", JSONObject().put("Host", JSONArray(hosts)).put("Connection", arr("keep-alive"))))))
                hostSni = hosts.firstOrNull().orEmpty()
            }
            "ws" -> { st.put("wsSettings", JSONObject().put("path", s.path.ifEmpty { "/" }).apply { if (s.host.isNotEmpty()) put("host", s.host) }); hostSni = s.host }
            "httpupgrade" -> { st.put("httpupgradeSettings", JSONObject().put("path", s.path.ifEmpty { "/" }).apply { if (s.host.isNotEmpty()) put("host", s.host) }); hostSni = s.host }
            "xhttp" -> {
                val x = JSONObject().put("path", s.path.ifEmpty { "/" }).put("mode", s.mode.ifEmpty { "auto" })
                if (s.host.isNotEmpty()) x.put("host", s.host)
                if (s.extra.isNotEmpty()) runCatching { x.put("extra", JSONObject(s.extra)) }
                st.put("xhttpSettings", x); hostSni = s.host
            }
            "grpc" -> { st.put("grpcSettings", JSONObject().put("serviceName", s.serviceName).put("multiMode", s.mode == "multi")
                .put("idle_timeout", 60).put("health_check_timeout", 20).apply { if (s.host.isNotEmpty()) put("authority", s.host) }); hostSni = s.host }
            "h2" -> { st.put("httpSettings", JSONObject().put("path", s.path.ifEmpty { "/" }).put("host", JSONArray(csv(s.host)))); hostSni = csv(s.host).firstOrNull().orEmpty() }
            "kcp" -> st.put("kcpSettings", JSONObject().put("header", JSONObject().put("type", s.headerType.ifEmpty { "none" })).apply { if (s.path.isNotEmpty()) put("seed", s.path) })
            "hysteria" -> st.put("hysteriaSettings", JSONObject().put("version", 2).put("auth", s.secret))
        }
        val security = when (s.protocol) { "hysteria2" -> "tls"; "wireguard", "socks", "shadowsocks" -> ""; else -> s.security }
        val fp = when {
            mask != null && mask.fingerprint.isNotEmpty() && s.protocol != "hysteria2" -> mask.fingerprint
            s.fp.isNotEmpty() -> s.fp
            else -> "chrome"
        }
        if (security == "tls" || security == "reality") {
            val sni0 = s.sni.ifEmpty { hostSni.takeIf { it.isNotEmpty() } ?: s.address.takeIf { !IPV4_LITERAL.matches(it) && ':' !in it }.orEmpty() }
            // «Белый SNI»: the certificate is pinned by hash, so the name in the hello is free to look like a Russian site
            val sni = if (mask != null && mask.sni.isNotEmpty() && security == "tls" && s.pcs.isNotEmpty() && s.protocol != "hysteria2") mask.sni else sni0
            val t = JSONObject()
            if (sni.isNotEmpty()) t.put("serverName", sni)
            if (s.protocol != "hysteria2") t.put("fingerprint", fp)
            if (security == "tls") {
                val alpn = if (mask != null && mask.alpn.isNotEmpty() && s.protocol != "hysteria2") csv(mask.alpn)
                    else csv(s.alpn).ifEmpty { if (s.protocol == "hysteria2") listOf("h3") else emptyList() }
                if (alpn.isNotEmpty()) t.put("alpn", JSONArray(alpn))
                // Xray 26 removed allowInsecure: self-signed servers are pinned by certificate SHA-256 instead.
                if (s.pcs.isNotEmpty()) t.put("pinnedPeerCertSha256", s.pcs)
                if (s.vcn.isNotEmpty()) t.put("verifyPeerCertByName", s.vcn)
                if (s.ech.isNotEmpty()) t.put("echConfigList", s.ech)
                st.put("security", "tls").put("tlsSettings", t)
            } else {
                t.put("publicKey", s.pbk).put("shortId", s.sid)
                if (s.spx.isNotEmpty()) t.put("spiderX", s.spx)
                if (s.pqv.isNotEmpty()) t.put("mldsa65Verify", s.pqv)
                st.put("security", "reality").put("realitySettings", t)
            }
        }
        val fm = JSONObject()
        if (mask != null && mask.packets.isNotEmpty() && s.isTcpBased) {
            val f = JSONObject().put("packets", mask.packets)
            if (mask.lengths.isNotEmpty()) f.put("lengths", JSONArray(csv(mask.lengths))).put("delays", JSONArray(csv(mask.delays)))
            else f.put("length", mask.length).put("delay", mask.delay)
            if (mask.maxSplit.isNotEmpty() && mask.maxSplit != "0") f.put("maxSplit", mask.maxSplit)
            fm.put("tcp", arr(JSONObject().put("type", "fragment").put("settings", f)))
        }
        if (s.protocol == "hysteria2" && s.obfsPassword.isNotEmpty())
            fm.put("udp", arr(JSONObject().put("type", "salamander").put("settings", JSONObject().put("password", s.obfsPassword))))
        val noise = mask?.let { Masks.noiseFor(it) }
        if (noise != null && !s.isTcpBased) {
            val u = fm.optJSONArray("udp") ?: JSONArray().also { fm.put("udp", it) }
            u.put(JSONObject().put("type", "noise").put("settings", JSONObject().put("noise", JSONArray(noise))))
        }
        if (mask != null && mask.hop.isNotEmpty() && s.protocol == "wireguard") {
            val u = fm.optJSONArray("udp") ?: JSONArray().also { fm.put("udp", it) }
            val h = JSONObject().put("interval", "10-20")
            if (mask.hop == Masks.HOP_WARP) h.put("mode", "intervalLocal,intervalRemote").put("remotePorts", Masks.WARP_PORTS)
            else h.put("mode", "intervalLocal")
            u.put(JSONObject().put("type", "udphop").put("settings", h))
        }
        if (fm.length() > 0) st.put("finalmask", fm)
        val sock = JSONObject().put("tcpKeepAliveInterval", 15)
        if (mask?.viaByeDpi == true && s.isTcpBased) sock.put("dialerProxy", dpiTag(mask.dpi))
        else if (mask != null) {
            if (mask.mss > 0 && s.isTcpBased) sock.put("tcpMaxSeg", mask.mss)
            if (mask.tfo && s.isTcpBased) sock.put("tcpFastOpen", true)
            if (mask.ipv6) sock.put("domainStrategy", "UseIPv6v4").put("happyEyeballs", JSONObject().put("tryDelayMs", 250)
                .put("prioritizeIPv6", true).put("interleave", 2).put("maxConcurrentTry", 4))
        }
        st.put("sockopt", sock)
        return st
    }

    fun byeDpiOutbound(port: Int, tag: String = BYEDPI_TAG): JSONObject = JSONObject().put("tag", tag).put("protocol", "socks")
        .put("settings", JSONObject().put("servers", arr(JSONObject().put("address", "127.0.0.1").put("port", port))))

    /** Outbound tag of a local DPI engine: the current strategy is "byedpi", fixed ones "dpi-<id>". */
    fun dpiTag(dpi: String): String = if (dpi.isEmpty() || dpi == Masks.CURRENT_DPI) BYEDPI_TAG else "dpi-" + dpi.replace(Regex("[^A-Za-z0-9_]"), "_")

    /** Masks whose engine is not running fall back to the current one, or to direct. */
    private fun resolveMask(m: Mask?, cur: Int?, ports: Map<String, Int>): Mask? = when {
        m == null || !m.viaByeDpi -> m
        m.dpi == Masks.CURRENT_DPI -> if (cur != null) m else m.copy(dpi = "")
        ports.containsKey(m.dpi) -> m
        cur != null -> m.copy(dpi = Masks.CURRENT_DPI)
        else -> m.copy(dpi = "")
    }

    private fun dpiOutbounds(outs: JSONArray, cur: Int?, ports: Map<String, Int>) {
        if (cur != null) outs.put(byeDpiOutbound(cur))
        ports.forEach { (id, p) -> outs.put(byeDpiOutbound(p, dpiTag(id))) }
    }

    private fun base(): JSONObject = JSONObject().put("log", JSONObject().put("loglevel", "warning"))

    /**
     * Config for the running VPN: SOCKS inbound 127.0.0.1:[Settings.socksPort] (hev-socks5-tunnel feeds the TUN into it),
     * one outbound per selected server, balancer + observatory across them, DNS via DoH through the tunnel.
     */
    fun vpnConfig(servers: List<Pair<Server, Mask?>>, settings0: Settings, byeDpiPort: Int?, socks: SocksAuth = SocksAuth(settings0.socksPort),
                  dpiPorts: Map<String, Int> = emptyMap(), listen: String = "127.0.0.1", httpPort: Int? = null): String {
        // Auto: servers when there are working ones, otherwise the DPI engine alone.
        val settings = if (settings0.mode == Mode.AUTO) settings0.copy(mode = if (servers.isEmpty()) Mode.BYEDPI else Mode.SERVERS) else settings0
        require(settings.mode == Mode.BYEDPI || servers.isNotEmpty()) { "Не выбран ни один сервер" }
        require(settings.mode == Mode.SERVERS || byeDpiPort != null) { "ByeDPI не запущен" }
        val c = base()
        val inSettings = JSONObject().put("udp", true).put("ip", "127.0.0.1")
        if (socks.auth) inSettings.put("auth", "password").put("accounts", arr(JSONObject().put("user", socks.user).put("pass", socks.pass)))
        else inSettings.put("auth", "noauth")
        fun sniff() = JSONObject().put("enabled", true).put("destOverride", arr("http", "tls", "quic")).put("routeOnly", true)
        val inbounds = arr(JSONObject().put("tag", "socks").put("listen", listen).put("port", socks.port)
            .put("protocol", "socks").put("settings", inSettings).put("sniffing", sniff()))
        // Proxy mode: an HTTP proxy next to SOCKS for apps/browsers that only know HTTP proxies.
        if (httpPort != null) inbounds.put(JSONObject().put("tag", "http").put("listen", listen).put("port", httpPort)
            .put("protocol", "http").put("settings", JSONObject()).put("sniffing", sniff()))
        c.put("inbounds", inbounds)
        val outs = JSONArray()
        val useServers = settings.mode != Mode.BYEDPI
        if (useServers) servers.forEachIndexed { i, (s, m) -> outs.put(outbound(s, "$PROXY_PREFIX$i", resolveMask(m, byeDpiPort, dpiPorts), settings.mux)) }
        outs.put(JSONObject().put("tag", "direct").put("protocol", "freedom").put("settings", JSONObject().put("domainStrategy", "UseIPv4")))
        outs.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        // MX/TXT/SRV/HTTPS records are passed on instead of dropped (apps used to hang waiting for them)
        outs.put(JSONObject().put("tag", "dns-out").put("protocol", "dns").put("settings", JSONObject().put("nonIPQuery", "skip")))
        dpiOutbounds(outs, byeDpiPort, if (useServers) dpiPorts else emptyMap())
        c.put("outbounds", outs)

        val many = useServers && servers.size > 1
        fun toMain(rule: JSONObject): JSONObject = when {
            !useServers -> rule.put("outboundTag", BYEDPI_TAG)
            many -> rule.put("balancerTag", "balancer")
            else -> rule.put("outboundTag", "${PROXY_PREFIX}0")
        }
        val dnsServers = JSONArray()
        val ruDns = settings.ruDns && settings.ruDirect && useServers
        if (ruDns) dnsServers.put(JSONObject().put("address", RU_DNS).put("port", 53)
            .put("domains", JSONArray(RU_DOMAINS)).put("skipFallback", true))
        // a chain, not one server: if Cloudflare DoH is slowed down here, Google, Quad9 and plain DNS through the tunnel answer
        (listOf(settings.dnsUrl) + DNS_FALLBACKS).distinct().forEach { dnsServers.put(it) }
        c.put("dns", JSONObject().put("servers", dnsServers).put("queryStrategy", "UseIPv4").put("tag", "dns-module")
            .put("hosts", JSONObject(DNS_HOSTS as Map<*, *>))
            // «Ускорение»: an expired answer is served at once and refreshed in the background (no wait on every open)
            .apply { if (settings.turbo) put("serveStale", true) })

        val rules = JSONArray()
        rules.put(JSONObject().put("inboundTag", arr("socks")).put("port", "53").put("outboundTag", "dns-out"))
        if (ruDns) rules.put(JSONObject().put("inboundTag", arr("dns-module")).put("ip", arr(RU_DNS)).put("outboundTag", "direct"))
        rules.put(toMain(JSONObject().put("inboundTag", arr("dns-module"))))
        rules.put(JSONObject().put("ip", arr("geoip:private")).put("outboundTag", "direct"))
        rules.put(JSONObject().put("ip", arr("::/0")).put("outboundTag", "block"))
        // WebRTC/STUN would reveal the real address to websites; QUIC can't be desynced, so TCP+TLS is forced.
        if (settings.blockStun) rules.put(JSONObject().put("network", "udp").put("port", "3478,5349,19302-19309").put("outboundTag", "block"))
        if (settings.blockQuic || !useServers) rules.put(JSONObject().put("network", "udp").put("port", "443").put("outboundTag", "block"))
        else if (settings.mode == Mode.HYBRID && settings.hybridDomains.isNotEmpty())
            rules.put(JSONObject().put("network", "udp").put("port", "443").put("domain", JSONArray(settings.hybridDomains)).put("outboundTag", "block"))
        // «Сайт не открывается?» — the user's own choice wins over everything below (RU direct, services, hybrid)
        if (settings.alwaysDirect.isNotEmpty()) rules.put(JSONObject().put("domain", JSONArray(settings.alwaysDirect.map { "domain:$it" })).put("outboundTag", "direct"))
        if (settings.alwaysVpn.isNotEmpty()) rules.put(toMain(JSONObject().put("domain", JSONArray(settings.alwaysVpn.map { "domain:$it" }))))
        if (settings.blockAds) rules.put(JSONObject().put("domain", arr("geosite:category-ads-all")).put("outboundTag", "block"))
        if (settings.mode == Mode.HYBRID && settings.hybridDomains.isNotEmpty())
            rules.put(JSONObject().put("domain", JSONArray(settings.hybridDomains)).put("outboundTag", BYEDPI_TAG))
        if (settings.ruDirect && useServers) {
            rules.put(JSONObject().put("domain", JSONArray(RU_DOMAINS)).put("outboundTag", "direct"))
            rules.put(JSONObject().put("ip", arr("geoip:ru")).put("outboundTag", "direct"))
        }
        val only = com.vlesscardvpn.core.Services.domains(settings.services)
        if (only.isNotEmpty()) {
            // "Only YouTube + Telegram through the VPN": those services → main route, everything else direct.
            rules.put(toMain(JSONObject().put("domain", JSONArray(only))))
            com.vlesscardvpn.core.Services.ips(settings.services).takeIf { it.isNotEmpty() }?.let { rules.put(toMain(JSONObject().put("ip", JSONArray(it)))) }
            rules.put(JSONObject().put("network", "tcp,udp").put("outboundTag", "direct"))
        } else rules.put(toMain(JSONObject().put("network", "tcp,udp")))
        val routing = JSONObject().put("domainStrategy", "AsIs").put("rules", rules)
        if (many) {
            routing.put("balancers", arr(JSONObject().put("tag", "balancer").put("selector", arr(PROXY_PREFIX))
                .put("strategy", JSONObject().put("type", settings.balance.xray)).put("fallbackTag", "${PROXY_PREFIX}0")))
            val probe = settings.testUrl
            if (settings.balance == Balance.LEAST_LOAD) {
                c.put("burstObservatory", JSONObject().put("subjectSelector", arr(PROXY_PREFIX)).put("pingConfig", JSONObject()
                    .put("destination", probe).put("interval", "1m").put("connectivity", "").put("timeout", "10s").put("sampling", 3)))
            } else {
                c.put("observatory", JSONObject().put("subjectSelector", arr(PROXY_PREFIX)).put("probeUrl", probe)
                    .put("probeInterval", "30s").put("enableConcurrency", true))
            }
        }
        c.put("routing", routing)
        // bufferSize (KB per connection): on arm64 Xray's default is only 4 KB — a video stream waits on the hand-off between
        // the app side and the server side. 64 KB keeps the pipe full (memory only while a connection is busy).
        c.put("policy", JSONObject().put("levels", JSONObject().put("0", JSONObject().put("handshake", 6).put("connIdle", 300)
            .put("uplinkOnly", 2).put("downlinkOnly", 5).apply { if (settings.turbo) put("bufferSize", 64) })))
        return c.toString(2)
    }

    /**
     * Test config: one SOCKS inbound per (server, mask) variant on consecutive loopback ports, each routed to its own
     * outbound. Lets the app test many servers/masks in parallel through one Xray instance.
     */
    fun testConfig(variants: List<Pair<Server, Mask?>>, ports: List<Int>, byeDpiPort: Int?, dpiPorts: Map<String, Int> = emptyMap()): String {
        require(ports.size == variants.size)
        val c = base()
        val ins = JSONArray(); val outs = JSONArray(); val rules = JSONArray()
        variants.forEachIndexed { i, (s, m) ->
            ins.put(JSONObject().put("tag", "t$i").put("listen", "127.0.0.1").put("port", ports[i]).put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", false)))
            outs.put(outbound(s, "v$i", resolveMask(m, byeDpiPort, dpiPorts)))
            rules.put(JSONObject().put("inboundTag", arr("t$i")).put("outboundTag", "v$i"))
        }
        dpiOutbounds(outs, byeDpiPort, dpiPorts)
        outs.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        c.put("inbounds", ins).put("outbounds", outs).put("routing", JSONObject().put("rules", rules))
        c.put("policy", JSONObject().put("levels", JSONObject().put("0", JSONObject().put("handshake", 8).put("connIdle", 30))))
        return c.toString()
    }

    /** ByeDPI-only test config: one SOCKS inbound routed to the ByeDPI outbound. */
    fun byeDpiTestConfig(port: Int, byeDpiPort: Int): String = base()
        .put("inbounds", arr(JSONObject().put("tag", "t0").put("listen", "127.0.0.1").put("port", port).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth"))))
        .put("outbounds", arr(byeDpiOutbound(byeDpiPort))).toString()
}
