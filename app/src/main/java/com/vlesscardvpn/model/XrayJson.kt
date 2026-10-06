package com.vlesscardvpn.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Xray JSON configs (one config, an array of configs as served by Marzban/Remnawave "v2rayNG JSON" subscriptions,
 * or a bare outbound): every proxy outbound becomes a server used as is (any transport Xray supports).
 */
object XrayJson {
    private val PROXY = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "wireguard", "socks", "http")

    fun parse(text: String, source: String = ""): List<Server> = runCatching {
        val t = text.trim()
        val configs: List<JSONObject> = if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } else listOf(JSONObject(t))
        configs.flatMap { c ->
            val outs = c.optJSONArray("outbounds")
            val list = if (outs != null) (0 until outs.length()).mapNotNull { outs.optJSONObject(it) } else listOf(c)
            val remarks = c.optString("remarks")
            list.filter { it.optString("protocol") in PROXY }.mapIndexedNotNull { i, o -> server(o, remarks.ifEmpty { o.optString("tag") }.let { if (i > 0) "$it #${i + 1}" else it }, source) }
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun server(o0: JSONObject, name: String, source: String): Server? {
        val o = JSONObject(o0.toString())
        o.remove("tag"); o.remove("proxySettings")
        // Chains to other outbounds of that config (fragment/noise helpers) can't work standalone.
        o.optJSONObject("streamSettings")?.optJSONObject("sockopt")?.remove("dialerProxy")
        val st = o.optJSONObject("settings") ?: JSONObject()
        val first = st.optJSONArray("vnext")?.optJSONObject(0) ?: st.optJSONArray("servers")?.optJSONObject(0)
        val (host, port) = when {
            first != null -> first.optString("address") to first.optInt("port")
            st.has("address") -> st.optString("address") to st.optInt("port")
            st.optJSONArray("peers") != null -> st.getJSONArray("peers").optJSONObject(0)?.optString("endpoint").orEmpty().let { ep ->
                ep.substringBeforeLast(':').trim('[', ']') to (ep.substringAfterLast(':').toIntOrNull() ?: 0) }
            else -> return null
        }
        if (host.isBlank() || port !in 1..65535) return null
        val json = o.toString()
        return Server(name = name.ifEmpty { "$host:$port" }, protocol = "xray", address = host, port = port,
            secret = Integer.toHexString(json.hashCode()), network = o.optString("protocol"), extra = json, source = source)
    }
}
