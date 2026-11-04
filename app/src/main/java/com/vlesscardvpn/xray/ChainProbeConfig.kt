package com.vlesscardvpn.xray

import com.vlesscardvpn.core.AutoChainSelection.Chain
import com.vlesscardvpn.core.AutoChainSelection.Layout
import org.json.JSONArray
import org.json.JSONObject

/** Isolated TCP configs for AutoChainRunner probes; final VPN config is built separately.
 * Passing through a local Tor listener is NOT the same as carrying Tor's guard
 * connection through a server. Reverse layouts require a separate Tor upstream
 * adapter and are intentionally rejected, never silently approximated.
 */
object ChainProbeConfig {
    val implemented = setOf(Layout.SERVER, Layout.DPI_SERVER, Layout.TOR, Layout.TOR_SERVER)
    fun build(chain: Chain, listenPort: Int, serverOutbound: JSONObject? = null,
              torPort: Int? = null, dpiPort: Int? = null): String {
        require(chain.layout in implemented) { "Chain adapter not implemented" }
        require(listenPort in 1024..65535)
        require(chain.layout.server == (serverOutbound != null)) { "Exact server outbound required" }
        if (chain.layout.tor) require(torPort != null && torPort in 1024..65535 && torPort != listenPort)
        if (chain.layout.dpi) require(dpiPort != null && dpiPort in 1024..65535 && dpiPort != listenPort)
        require(!(chain.layout.tor || chain.layout.dpi) || !chain.layout.server || chain.tcpServer) { "TCP server required" }
        require(chain.serverExitAllowed) { "Disallowed server exit" }
        val outbounds = JSONArray()
        val main = if (serverOutbound != null) {
            JSONObject(serverOutbound.toString()).apply {
                require(optString("protocol") in setOf("vless", "vmess", "trojan", "shadowsocks", "socks"))
                require(!has("proxySettings")) { "Nested proxy config is not supported" }
                put("tag", "chain-main")
                val stream = optJSONObject("streamSettings") ?: JSONObject().also { put("streamSettings", it) }
                require(stream.optString("network", "tcp") !in setOf("kcp", "quic", "hysteria")) { "TCP transport required" }
                val sock = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
                require(!sock.has("dialerProxy")) { "Existing dialer must not be silently overwritten" }
                when (chain.layout) {
                    Layout.DPI_SERVER -> sock.put("dialerProxy", "chain-dpi")
                    Layout.TOR_SERVER -> sock.put("dialerProxy", "chain-tor")
                    else -> Unit
                }
            }
        } else socks("chain-main", torPort!!)
        outbounds.put(main)
        if (chain.layout == Layout.TOR_SERVER) outbounds.put(socks("chain-tor", torPort!!))
        if (chain.layout == Layout.DPI_SERVER) outbounds.put(socks("chain-dpi", dpiPort!!))
        outbounds.put(JSONObject().put("tag", "chain-block").put("protocol", "blackhole"))
        return JSONObject().put("log", JSONObject().put("loglevel", "error"))
            .put("inbounds", JSONArray().put(JSONObject().put("tag", "chain-in").put("listen", "127.0.0.1")
                .put("port", listenPort).put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", false))))
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()
                .put(JSONObject().put("type", "field").put("network", "udp").put("outboundTag", "chain-block"))
                .put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("chain-in")).put("outboundTag", "chain-main"))))
            .toString()
    }
    private fun socks(tag: String, port: Int) = JSONObject().put("tag", tag).put("protocol", "socks")
        .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", "127.0.0.1").put("port", port))))
}
