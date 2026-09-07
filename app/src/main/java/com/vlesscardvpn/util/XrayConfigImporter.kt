package com.vlesscardvpn.util

import com.vlesscardvpn.domain.VlessConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Imports Xray-core JSON configs (v2ray/xray format) and converts them to VlessConfig.
 * Supports both client-side outbounds and full config.json files.
 *
 * Format reference: https://xtls.github.io/config/
 *
 * Xray config structure:
 * {
 *   "outbounds": [{ "protocol": "vless", "settings": {...}, "streamSettings": {...} }],
 *   "inbounds": [...],
 *   "routing": {...}
 * }
 */
object XrayConfigImporter {

    data class XrayImportResult(
        val configs: List<VlessConfig>,
        val warnings: List<String> = emptyList(),
        val routingRules: List<String> = emptyList()
    )

    fun importFromJson(jsonString: String): XrayImportResult {
        val warnings = mutableListOf<String>()
        val configs = mutableListOf<VlessConfig>()
        val routingRules = mutableListOf<String>()

        return try {
            val root = JSONObject(jsonString.trim())

            // Parse outbounds
            val outbounds = root.optJSONArray("outbounds")
            if (outbounds != null) {
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.getJSONObject(i)
                    val protocol = outbound.optString("protocol", "").lowercase()

                    when (protocol) {
                        "vless" -> parseVlessOutbound(outbound)?.let { configs.add(it) }
                        "vmess" -> parseVmessOutbound(outbound)?.let { configs.add(it) }
                        "trojan" -> parseTrojanOutbound(outbound)?.let { configs.add(it) }
                        "shadowsocks" -> parseShadowsocksOutbound(outbound)?.let { configs.add(it) }
                        "hysteria2", "hysteria" -> parseHysteria2Outbound(outbound)?.let { configs.add(it) }
                        "freedom", "direct", "block", "dns", "blackhole" -> { /* skip internal outbounds */ }
                        else -> warnings.add("Unknown protocol: $protocol")
                    }
                }
            }

            // Extract routing rules for informational purposes
            val routing = root.optJSONObject("routing")
            if (routing != null) {
                val rules = routing.optJSONArray("rules")
                if (rules != null) {
                    for (i in 0 until rules.length()) {
                        val rule = rules.getJSONObject(i)
                        val domain = rule.optJSONArray("domain")
                        val ip = rule.optJSONArray("ip")
                        val outboundTag = rule.optString("outboundTag", "")
                        if (domain != null) {
                            routingRules.add("domain:${domain.length()} items → $outboundTag")
                        }
                        if (ip != null) {
                            routingRules.add("ip:${ip.length()} items → $outboundTag")
                        }
                    }
                }
            }

            XrayImportResult(configs, warnings, routingRules)
        } catch (e: Exception) {
            XrayImportResult(emptyList(), listOf("Failed to parse Xray config: ${e.message}"))
        }
    }

    private fun parseVlessOutbound(outbound: JSONObject): VlessConfig? {
        return try {
            val settings = outbound.optJSONObject("settings") ?: return null
            val vnext = settings.optJSONArray("vnext")
            val server = if (vnext != null && vnext.length() > 0) {
                vnext.getJSONObject(0)
            } else return null

            val address = server.optString("address", "")
            val port = server.optInt("port", 443)
            val users = server.optJSONArray("users")
            val user = if (users != null && users.length() > 0) users.getJSONObject(0) else return null

            val uuid = user.optString("id", "")
            val flow = user.optString("flow", "xtls-rprx-vision")
            val encryption = user.optString("encryption", "none")

            val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()
            val security = streamSettings.optString("security", "none")
            val network = streamSettings.optString("network", "tcp")

            val realitySettings = streamSettings.optJSONObject("realitySettings")
            val tlsSettings = streamSettings.optJSONObject("tlsSettings")

            val sni = when {
                realitySettings?.optString("serverName", "")?.isNotBlank() == true -> realitySettings.optString("serverName")
                tlsSettings?.optString("serverName", "")?.isNotBlank() == true -> tlsSettings.optString("serverName")
                else -> "yandex.ru"
            }

            val fingerprint = when {
                realitySettings?.optString("fingerprint", "")?.isNotBlank() == true -> realitySettings.optString("fingerprint")
                tlsSettings?.optString("fingerprint", "")?.isNotBlank() == true -> tlsSettings.optString("fingerprint")
                else -> "chrome"
            }

            val publicKey = realitySettings?.optString("publicKey", "") ?: ""
            val shortId = realitySettings?.optString("shortId", "") ?: ""

            // Parse transport settings
            val wsSettings = streamSettings.optJSONObject("wsSettings")
            val grpcSettings = streamSettings.optJSONObject("grpcSettings")
            val h2Settings = streamSettings.optJSONObject("httpSettings")

            val wsHost = wsSettings?.optJSONObject("headers")?.optString("Host", "") ?: ""
            val wsPath = wsSettings?.optString("path", "/") ?: "/"
            val serviceName = grpcSettings?.optString("serviceName", "") ?: ""

            val transport = when (network.lowercase()) {
                "ws", "websocket" -> "ws"
                "grpc", "gun" -> "grpc"
                "h2", "http" -> "h2"
                "tcp" -> "tcp"
                "kcp" -> "kcp"
                "quic" -> "quic"
                else -> "tcp"
            }

            VlessConfig(
                id = UUID.randomUUID().toString(),
                name = outbound.optString("tag", "Xray VLESS"),
                address = address,
                port = port,
                uuid = uuid,
                protocolType = "vless",
                flow = flow,
                security = if (security == "reality") "reality" else if (security == "tls") "tls" else "none",
                sni = sni,
                fingerprint = fingerprint,
                publicKey = publicKey,
                shortId = shortId,
                remark = "Xray: ${outbound.optString("tag", "VLESS")}",
                isFree = false,
                source = "xray-import",
                transport = transport,
                wsHost = wsHost,
                wsPath = wsPath,
                serviceName = serviceName
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVmessOutbound(outbound: JSONObject): VlessConfig? {
        return try {
            val settings = outbound.optJSONObject("settings") ?: return null
            val vnext = settings.optJSONArray("vnext")
            val server = if (vnext != null && vnext.length() > 0) vnext.getJSONObject(0) else return null

            val address = server.optString("address", "")
            val port = server.optInt("port", 443)
            val users = server.optJSONArray("users")
            val user = if (users != null && users.length() > 0) users.getJSONObject(0) else return null

            val uuid = user.optString("id", "")
            val security = user.optString("security", "auto")

            val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()
            val tlsSettings = streamSettings.optJSONObject("tlsSettings")
            val tlsSecurity = streamSettings.optString("security", "none")
            val network = streamSettings.optString("network", "tcp")

            val sni = tlsSettings?.optString("serverName", "") ?: "yandex.ru"

            val wsSettings = streamSettings.optJSONObject("wsSettings")
            val wsPath = wsSettings?.optString("path", "/") ?: "/"
            val wsHost = wsSettings?.optJSONObject("headers")?.optString("Host", "") ?: ""

            VlessConfig(
                id = UUID.randomUUID().toString(),
                name = outbound.optString("tag", "Xray VMess"),
                address = address,
                port = port,
                uuid = uuid,
                protocolType = "vmess",
                security = if (tlsSecurity == "tls") "tls" else "none",
                sni = sni,
                remark = "Xray: ${outbound.optString("tag", "VMess")}",
                isFree = false,
                source = "xray-import",
                transport = if (network == "ws") "ws" else "tcp",
                wsPath = wsPath,
                wsHost = wsHost
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTrojanOutbound(outbound: JSONObject): VlessConfig? {
        return try {
            val settings = outbound.optJSONObject("settings") ?: return null
            val servers = settings.optJSONArray("servers")
            val server = if (servers != null && servers.length() > 0) servers.getJSONObject(0) else return null

            val address = server.optString("address", "")
            val port = server.optInt("port", 443)
            val password = server.optString("password", "")

            val streamSettings = outbound.optJSONObject("streamSettings") ?: JSONObject()
            val tlsSettings = streamSettings.optJSONObject("tlsSettings")
            val sni = tlsSettings?.optString("serverName", "") ?: address

            VlessConfig(
                id = UUID.randomUUID().toString(),
                name = outbound.optString("tag", "Xray Trojan"),
                address = address,
                port = port,
                uuid = password,
                protocolType = "trojan",
                security = "tls",
                sni = sni,
                remark = "Xray: ${outbound.optString("tag", "Trojan")}",
                isFree = false,
                source = "xray-import"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseShadowsocksOutbound(outbound: JSONObject): VlessConfig? {
        return try {
            val settings = outbound.optJSONObject("settings") ?: return null
            val servers = settings.optJSONArray("servers")
            val server = if (servers != null && servers.length() > 0) servers.getJSONObject(0) else return null

            val address = server.optString("address", "")
            val port = server.optInt("port", 8388)
            val method = server.optString("method", "aes-256-gcm")
            val password = server.optString("password", "")

            VlessConfig(
                id = UUID.randomUUID().toString(),
                name = outbound.optString("tag", "Xray Shadowsocks"),
                address = address,
                port = port,
                uuid = "$method:$password",
                protocolType = "shadowsocks",
                security = "none",
                remark = "Xray: ${outbound.optString("tag", "SS")}",
                isFree = false,
                source = "xray-import"
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseHysteria2Outbound(outbound: JSONObject): VlessConfig? {
        return try {
            val server = outbound.optString("server", "")
            val port = outbound.optInt("server_port", outbound.optInt("port", 443))
            val password = outbound.optString("password", outbound.optString("auth", ""))
            val obfs = outbound.optString("obfs", "")
            val obfsPassword = outbound.optString("obfs-password", "")

            val tls = outbound.optJSONObject("tls")
            val sni = tls?.optString("server_name", "") ?: server

            // Encode full Hysteria2 config as uuid for storage
            val encoded = buildString {
                append("hysteria2:")
                append("password=$password")
                if (obfs.isNotBlank()) append(";obfs=$obfs")
                if (obfsPassword.isNotBlank()) append(";obfs-password=$obfsPassword")
                append(";sni=$sni")
            }

            VlessConfig(
                id = UUID.randomUUID().toString(),
                name = outbound.optString("tag", "Hysteria2"),
                address = server,
                port = port,
                uuid = encoded,
                protocolType = "hysteria2",
                security = "tls",
                sni = sni,
                remark = "Hysteria2: ${outbound.optString("tag", server)}",
                isFree = false,
                source = "xray-import"
            )
        } catch (e: Exception) {
            null
        }
    }
}