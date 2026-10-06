package com.vlesscardvpn.model

import org.json.JSONObject
import java.net.URLDecoder

/** Parses share links and subscription bodies (plain or base64) into [Server]s. Never throws. */
object LinkParser {
    private val schemes = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", "wireguard://", "wg://", "socks://", "socks5://")

    fun parseMany(text: String, source: String = ""): List<Server> {
        var body = text.trim().removePrefix("\uFEFF")
        if (body.startsWith("{") || body.startsWith("[")) XrayJson.parse(body, source).takeIf { it.isNotEmpty() }?.let { return it }
        if (body.contains("[Interface]", true) && body.contains("[Peer]", true)) return listOfNotNull(wgConf(body, source))
        if (schemes.none { body.contains(it, ignoreCase = true) }) {
            body = Base64.decodeToString(body.replace("\n", "").replace("\r", "").trim()) ?: return emptyList()
        }
        return body.split('\n', '\r', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { parse(it, source) }.distinctBy { it.id }
    }

    fun parse(link: String, source: String = ""): Server? = runCatching {
        val l = link.trim()
        when {
            l.startsWith("vless://", true) -> standard(l, "vless", source)
            l.startsWith("trojan://", true) -> standard(l, "trojan", source)
            l.startsWith("hysteria2://", true) || l.startsWith("hy2://", true) -> standard(l, "hysteria2", source)
            l.startsWith("vmess://", true) -> vmess(l, source)
            l.startsWith("ss://", true) -> shadowsocks(l, source)
            l.startsWith("wireguard://", true) || l.startsWith("wg://", true) -> wireguard(l, source)
            l.startsWith("socks://", true) || l.startsWith("socks5://", true) -> socks(l, source)
            else -> null
        }
    }.getOrNull()?.takeIf { it.port in 1..65535 && it.address.isNotBlank() && it.address.length <= 253 }

    private fun dec(s: String): String = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun query(q: String): Map<String, String> = q.split('&').filter { '=' in it }
        .associate { it.substringBefore('=').lowercase() to dec(it.substringAfter('=')) }

    private class Parts(val user: String, val host: String, val port: Int, val q: Map<String, String>, val name: String)

    private fun split(link: String): Parts {
        val noScheme = link.substringAfter("://")
        val name = if ('#' in noScheme) dec(noScheme.substringAfter('#')).trim() else ""
        val main = noScheme.substringBefore('#')
        val beforeQuery = main.substringBefore('?').trimEnd('/')
        val q = if ('?' in main) query(main.substringAfter('?')) else emptyMap()
        val user = if ('@' in beforeQuery) dec(beforeQuery.substringBeforeLast('@')) else ""
        val hostPort = beforeQuery.substringAfterLast('@')
        val (host, port) = hostPort(hostPort)
        return Parts(user, host, port, q, name)
    }

    private fun hostPort(hp: String): Pair<String, Int> {
        val clean = hp.substringBefore('/')
        return if (clean.startsWith("[")) clean.substring(1, clean.indexOf(']')) to clean.substringAfter("]:").toInt()
        else clean.substringBeforeLast(':') to clean.substringAfterLast(':').toInt()
    }

    private fun standard(link: String, protocol: String, source: String): Server {
        val p = split(link)
        val q = p.q
        val security = when {
            protocol == "hysteria2" -> "tls"
            else -> q["security"].orEmpty().lowercase().let { if (it == "none") "" else it }.ifEmpty { if (protocol == "trojan") "tls" else "" }
        }
        val network = when (val t = q["type"].orEmpty().lowercase()) {
            "", "raw" -> "tcp"; "http" -> "h2"; "splithttp" -> "xhttp"; else -> t
        }
        return Server(
            name = p.name.ifEmpty { "${p.host}:${p.port}" }, protocol = protocol, address = p.host, port = p.port,
            secret = p.user, flow = q["flow"].orEmpty(), encryption = q["encryption"].orEmpty().ifEmpty { "none" },
            network = if (protocol == "hysteria2") "hysteria" else network, security = security,
            sni = q["sni"] ?: q["peer"] ?: "", fp = q["fp"].orEmpty(), alpn = q["alpn"].orEmpty(),
            pbk = q["pbk"].orEmpty(), sid = q["sid"].orEmpty(), spx = q["spx"].orEmpty(), pqv = q["pqv"].orEmpty(),
            host = q["host"].orEmpty(), path = q["path"].orEmpty(), serviceName = q["servicename"].orEmpty(),
            mode = q["mode"].orEmpty(), headerType = q["headertype"].orEmpty(),
            insecure = q["allowinsecure"] == "1" || q["insecure"] == "1" || q["allowinsecure"] == "true",
            ech = q["ech"].orEmpty(), pcs = q["pcs"] ?: q["pinsha256"] ?: "", vcn = q["vcn"].orEmpty(), obfsPassword = if (q["obfs"] == "salamander") q["obfs-password"].orEmpty() else "",
            extra = q["extra"].orEmpty(), source = source,
        )
    }

    private fun vmess(link: String, source: String): Server? {
        val json = Base64.decodeToString(link.substringAfter("://").substringBefore('#')) ?: return null
        val o = JSONObject(json)
        val net = o.optString("net", "tcp").lowercase().let { if (it == "http") "h2" else if (it == "splithttp") "xhttp" else it.ifEmpty { "tcp" } }
        val tls = o.optString("tls").lowercase().let { if (it == "none") "" else it }
        return Server(
            name = o.optString("ps").ifEmpty { o.optString("add") }, protocol = "vmess", address = o.optString("add"),
            port = o.optString("port").toInt(), secret = o.optString("id"), method = o.optString("scy", "auto").ifEmpty { "auto" },
            network = net, security = tls, sni = o.optString("sni"), fp = o.optString("fp"), alpn = o.optString("alpn"),
            host = o.optString("host"), path = if (net == "grpc") "" else o.optString("path"),
            serviceName = if (net == "grpc") o.optString("path") else "", headerType = o.optString("type").let { if (it == "none") "" else it },
            mode = if (net == "xhttp") o.optString("type") else "", source = source,
        )
    }

    private fun shadowsocks(link: String, source: String): Server? {
        val body = link.substringAfter("://")
        val name = if ('#' in body) dec(body.substringAfter('#')).trim() else ""
        val main = body.substringBefore('#')
        val q = if ('?' in main) query(main.substringAfter('?')) else emptyMap()
        if (!q["plugin"].isNullOrEmpty()) return null // obfs/v2ray plugins are not supported by Xray
        val core = main.substringBefore('?').trimEnd('/')
        val (cred, hostPortStr) = if ('@' in core) {
            val u = core.substringBeforeLast('@')
            (Base64.decodeToString(dec(u)) ?: dec(u)) to core.substringAfterLast('@')
        } else {
            val full = Base64.decodeToString(core) ?: return null
            full.substringBeforeLast('@') to full.substringAfterLast('@')
        }
        val (host, port) = hostPort(hostPortStr)
        val method = cred.substringBefore(':').lowercase()
        if (method !in SS_METHODS) return null
        return Server(name = name.ifEmpty { "$host:$port" }, protocol = "shadowsocks", address = host, port = port,
            secret = cred.substringAfter(':'), method = method, source = source)
    }

    /** v2rayNG / Hiddify: wireguard://<private key>@host:port?publickey=…&address=…&reserved=1,2,3&mtu=1280#name */
    private fun wireguard(link: String, source: String): Server? {
        val p = split(link)
        val q = p.q
        val pub = q["publickey"] ?: q["peer_public_key"] ?: q["public_key"] ?: return null
        return Server(name = p.name.ifEmpty { "WG ${p.host}" }, protocol = "wireguard", address = p.host, port = p.port, secret = p.user,
            pbk = pub, localAddress = (q["address"] ?: q["ip"] ?: "172.16.0.2/32").replace(" ", ""), reserved = q["reserved"].orEmpty().replace(" ", ""),
            mtu = q["mtu"]?.toIntOrNull() ?: 0, psk = q["presharedkey"] ?: q["psk"] ?: "", network = "udp", source = source)
    }

    /** WireGuard .conf (e.g. Cloudflare WARP). AmneziaWG junk options are not supported by Xray and are ignored. */
    fun wgConf(text: String, source: String = ""): Server? = runCatching {
        val kv = text.lines().map { it.trim() }.filter { '=' in it && !it.startsWith("#") }
            .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
        val ep = kv["endpoint"] ?: return null
        val (host, port) = hostPort(ep)
        Server(name = "WireGuard $host", protocol = "wireguard", address = host, port = port, secret = kv["privatekey"] ?: return null,
            pbk = kv["publickey"] ?: return null, localAddress = kv["address"].orEmpty().replace(" ", "").ifEmpty { "172.16.0.2/32" },
            mtu = kv["mtu"]?.toIntOrNull() ?: 0, psk = kv["presharedkey"].orEmpty(), network = "udp", source = source)
    }.getOrNull()

    /** socks://base64(user:pass)@host:port or socks5://user:pass@host:port */
    private fun socks(link: String, source: String): Server {
        val p = split(link)
        val cred = if (p.user.isEmpty()) "" else (if (':' in p.user) p.user else Base64.decodeToString(p.user) ?: p.user)
        return Server(name = p.name.ifEmpty { "SOCKS ${p.host}" }, protocol = "socks", address = p.host, port = p.port,
            user = cred.substringBefore(':', ""), secret = if (':' in cred) cred.substringAfter(':') else "", source = source)
    }

    val SS_METHODS = setOf("aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305", "xchacha20-poly1305",
        "xchacha20-ietf-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "none", "plain")

    /** Share link back (for copy/export). */
    fun toLink(s: Server): String {
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val name = enc(s.name)
        if (s.protocol == "xray") return s.extra
        if (s.protocol == "wireguard") {
            val q = listOf("publickey" to s.pbk, "address" to s.localAddress, "reserved" to s.reserved, "mtu" to (if (s.mtu > 0) s.mtu.toString() else ""),
                "presharedkey" to s.psk).filter { it.second.isNotEmpty() }
            val host = if (':' in s.address) "[${s.address}]" else s.address
            return "wireguard://${enc(s.secret)}@$host:${s.port}?" + q.joinToString("&") { "${it.first}=${enc(it.second)}" } + "#$name"
        }
        if (s.protocol == "socks") return "socks://" + (if (s.user.isNotEmpty() || s.secret.isNotEmpty()) Base64.encode("${s.user}:${s.secret}") + "@" else "") + "${s.address}:${s.port}#$name"
        if (s.protocol == "shadowsocks") return "ss://" + Base64.encode("${s.method}:${s.secret}") + "@${s.address}:${s.port}#$name"
        if (s.protocol == "vmess") {
            val o = JSONObject().put("v", "2").put("ps", s.name).put("add", s.address).put("port", s.port.toString()).put("id", s.secret)
                .put("scy", s.method.ifEmpty { "auto" }).put("net", s.network).put("type", s.headerType.ifEmpty { "none" })
                .put("host", s.host).put("path", if (s.network == "grpc") s.serviceName else s.path).put("tls", s.security)
                .put("sni", s.sni).put("fp", s.fp).put("alpn", s.alpn)
            return "vmess://" + Base64.encode(o.toString())
        }
        val q = linkedMapOf<String, String>()
        if (s.protocol == "vless") q["encryption"] = s.encryption
        if (s.protocol != "hysteria2") { q["type"] = s.network; q["security"] = s.security.ifEmpty { "none" } }
        listOf("flow" to s.flow, "sni" to s.sni, "fp" to s.fp, "alpn" to s.alpn, "pbk" to s.pbk, "sid" to s.sid, "spx" to s.spx,
            "pqv" to s.pqv, "host" to s.host, "path" to s.path, "serviceName" to s.serviceName, "mode" to s.mode,
            "headerType" to s.headerType, "ech" to s.ech, "pcs" to s.pcs, "vcn" to s.vcn, "extra" to s.extra).forEach { (k, v) -> if (v.isNotEmpty()) q[k] = v }
        if (s.obfsPassword.isNotEmpty()) { q["obfs"] = "salamander"; q["obfs-password"] = s.obfsPassword }
        if (s.insecure) q["insecure"] = "1"
        val host = if (':' in s.address) "[${s.address}]" else s.address
        return "${s.protocol}://${enc(s.secret)}@$host:${s.port}?" + q.entries.joinToString("&") { "${it.key}=${enc(it.value)}" } + "#$name"
    }
}

/** Minimal Base64 (standard + URL-safe, padding optional) usable on Android 7 and in JVM tests. */
object Base64 {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun decode(input: String): ByteArray? {
        val s = input.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        if (s.isEmpty() || s.any { STD.indexOf(it) < 0 }) return null
        if (s.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
        for (c in s) { buf = (buf shl 6) or STD.indexOf(c); bits += 6; if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) } }
        return out.toByteArray()
    }
    fun decodeToString(input: String): String? = decode(input)?.let { b ->
        val d = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        runCatching { d.decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrNull()
    }
    fun encode(text: String): String {
        val b = text.toByteArray(Charsets.UTF_8); val sb = StringBuilder(); var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i); var v = 0
            for (k in 0 until 3) v = (v shl 8) or (if (k < n) b[i + k].toInt() and 0xFF else 0)
            for (k in 0 until 4) sb.append(if (k <= n) STD[(v shr (18 - 6 * k)) and 63] else '=')
            i += 3
        }
        return sb.toString()
    }
}
