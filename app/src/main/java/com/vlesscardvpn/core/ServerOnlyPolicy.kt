package com.vlesscardvpn.core

import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.Settings
import org.json.JSONObject
import java.net.InetAddress

object ServerOnlyPolicy {
    private val remoteProtocols = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria", "hysteria2", "wireguard", "socks", "http")

    fun isRemoteServer(server: Server): Boolean {
        val protocol = if (server.protocol == "xray") {
            runCatching { JSONObject(server.extra).optString("protocol").lowercase() }.getOrDefault("")
        } else server.protocol.lowercase()
        return protocol in remoteProtocols && server.port in 1..65535 && !isLocalHost(server.address)
    }

    fun userRoute(server: Server): Boolean = isRemoteServer(server) ||
        // Only debug builds can use an in-process encrypted-protocol fixture. Never admit local Tor/SOCKS.
        com.vlesscardvpn.BuildConfig.DEBUG && server.protocol in setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2") &&
        server.address in setOf("127.0.0.1", "::1") && server.port in 1..65535

    private fun isLocalHost(value: String): Boolean {
        val host = value.trim().trim('[', ']').trimEnd('.').lowercase()
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".lan") || host.endsWith(".internal") || host == "home.arpa" || host.endsWith(".home.arpa")) return true
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val p = parts.map { it.toInt() }
            return p[0] == 0 || p[0] == 127 || p[0] >= 224 ||
                p[0] == 169 && p[1] == 254
        }
        if (':' in host) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
            val bytes = address.address
            val uniqueLocal = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
            return uniqueLocal || address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
        }
        return false
    }

    fun normalize(settings: Settings): Settings {
        val normalized = settings.copy(
            mode = if (settings.mode == Mode.BYEDPI || settings.mode == Mode.HYBRID) Mode.AUTO else settings.mode,
            ruDirect = false,
            ruDns = false,
            apps = emptyList(),
            onlyApps = false,
            perApp = false,
            proxyOnly = false,
            lanShare = false,
            services = emptyList(),
            torRouting = false,
            ruAppsDirect = false,
            coverTraffic = false,
            torDirect = false,
            torAuto = false,
            torRotation = settings.torAll && settings.torRotation,
            alwaysDirect = emptyList(),
            smartYoutube = false,
            ytDpi = emptyMap(),
        )
        return if (normalized == settings) settings else normalized
    }
}
