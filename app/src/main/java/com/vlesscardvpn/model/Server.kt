package com.vlesscardvpn.model

import org.json.JSONObject

/** One proxy server parsed from a share link (vless/vmess/trojan/ss/hysteria2). Immutable. */
data class Server(
    val name: String,
    val protocol: String,          // vless, vmess, trojan, shadowsocks, hysteria2
    val address: String,
    val port: Int,
    val secret: String,            // uuid / password / auth
    val method: String = "",       // shadowsocks cipher, vmess security
    val flow: String = "",
    val encryption: String = "none",
    val network: String = "tcp",   // tcp, ws, grpc, xhttp, httpupgrade, h2, kcp
    val security: String = "",     // "", tls, reality
    val sni: String = "",
    val fp: String = "",
    val alpn: String = "",
    val pbk: String = "",
    val sid: String = "",
    val spx: String = "",
    val pqv: String = "",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    val mode: String = "",
    val headerType: String = "",
    val insecure: Boolean = false,
    val ech: String = "",
    val pcs: String = "",          // pinnedPeerCertSha256 (replaces allowInsecure in Xray 26)
    val vcn: String = "",          // verifyPeerCertByName
    val obfsPassword: String = "",
    val extra: String = "",
    val source: String = "",
) {
    /** Stable identity: same endpoint + credentials + transport = same server, regardless of name. */
    val id: String get() = Integer.toHexString(listOf(protocol, address.lowercase(), port, secret, network, security, sni, pbk, sid, path, host, serviceName, flow).joinToString("|").hashCode()) +
        Integer.toHexString("$address$secret$port".hashCode())

    val isTcpBased: Boolean get() = protocol != "hysteria2" && network != "kcp"
    val label: String get() = buildString {
        append(when (protocol) { "shadowsocks" -> "SS"; "hysteria2" -> "Hy2"; else -> protocol.uppercase() })
        if (protocol != "hysteria2" && protocol != "shadowsocks") append(" · ").append(network)
        if (security.isNotEmpty()) append(" · ").append(security)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("protocol", protocol); put("address", address); put("port", port); put("secret", secret)
        fun opt(k: String, v: String) { if (v.isNotEmpty()) put(k, v) }
        opt("method", method); opt("flow", flow); if (encryption != "none") put("encryption", encryption)
        if (network != "tcp") put("network", network); opt("security", security); opt("sni", sni); opt("fp", fp)
        opt("alpn", alpn); opt("pbk", pbk); opt("sid", sid); opt("spx", spx); opt("pqv", pqv); opt("host", host)
        opt("path", path); opt("serviceName", serviceName); opt("mode", mode); opt("headerType", headerType)
        if (insecure) put("insecure", true); opt("ech", ech); opt("pcs", pcs); opt("vcn", vcn); opt("obfsPassword", obfsPassword); opt("extra", extra); opt("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject) = Server(
            name = o.optString("name"), protocol = o.getString("protocol"), address = o.getString("address"),
            port = o.getInt("port"), secret = o.optString("secret"), method = o.optString("method"), flow = o.optString("flow"),
            encryption = o.optString("encryption", "none"), network = o.optString("network", "tcp"), security = o.optString("security"),
            sni = o.optString("sni"), fp = o.optString("fp"), alpn = o.optString("alpn"), pbk = o.optString("pbk"), sid = o.optString("sid"),
            spx = o.optString("spx"), pqv = o.optString("pqv"), host = o.optString("host"), path = o.optString("path"),
            serviceName = o.optString("serviceName"), mode = o.optString("mode"), headerType = o.optString("headerType"),
            insecure = o.optBoolean("insecure"), ech = o.optString("ech"), pcs = o.optString("pcs"), vcn = o.optString("vcn"), obfsPassword = o.optString("obfsPassword"),
            extra = o.optString("extra"), source = o.optString("source"),
        )
    }
}

/** Last test result for a server (all measured from the phone, outside the VPN). */
data class ServerState(
    val selected: Boolean = false,
    val tcpMs: Int = -1,           // TCP connect to server; -1 untested, 0 failed
    val realMs: Int = -1,          // HTTPS through the server (Xray); -1 untested, 0 failed
    val bigOk: Boolean? = null,    // 256 KB download passed (no "16 KB freeze")
    val ytOk: Boolean? = null,     // YouTube reachable through the server
    val maskId: String = "",       // chosen masking (Masks.byId), "" = automatic default
    val checkedAt: Long = 0,
    val okCount: Int = 0,
    val failCount: Int = 0,
    /** Other masks that passed the last search, fastest first (used by "менять маскировку"). */
    val goodMasks: List<String> = emptyList(),
) {
    val works: Boolean get() = realMs > 0 && bigOk != false
    fun toJson(): JSONObject = JSONObject().apply {
        if (selected) put("sel", true); put("tcp", tcpMs); put("real", realMs); bigOk?.let { put("big", it) }; ytOk?.let { put("yt", it) }
        if (maskId.isNotEmpty()) put("mask", maskId); put("at", checkedAt); put("ok", okCount); put("fail", failCount)
        if (goodMasks.isNotEmpty()) put("good", org.json.JSONArray(goodMasks))
    }
    companion object {
        fun fromJson(o: JSONObject) = ServerState(
            selected = o.optBoolean("sel"), tcpMs = o.optInt("tcp", -1), realMs = o.optInt("real", -1),
            bigOk = if (o.has("big")) o.optBoolean("big") else null, ytOk = if (o.has("yt")) o.optBoolean("yt") else null,
            maskId = o.optString("mask"), checkedAt = o.optLong("at"), okCount = o.optInt("ok"), failCount = o.optInt("fail"),
            goodMasks = o.optJSONArray("good")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }
}
