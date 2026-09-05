package com.vlesscardvpn.util

import android.util.Base64
import com.vlesscardvpn.domain.VlessConfig
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object UniversalConfigParser {

    fun parseAny(raw: String): List<VlessConfig> {
        val trimmed = raw.trim()
        val results = mutableListOf<VlessConfig>()

        // Check if raw is base64 encoded subscription
        val decodedText = tryDecodeBase64(trimmed) ?: trimmed
        
        decodedText.lines().forEach { line ->
            val l = line.trim()
            if (l.isNotBlank()) {
                val parsed = parseSingleUri(l)
                if (parsed != null) {
                    results.add(parsed)
                }
            }
        }
        return results
    }

    fun parseSingleUri(uri: String): VlessConfig? {
        val trimmed = uri.trim()
        return when {
            trimmed.startsWith("vless://") -> parseVless(trimmed)
            trimmed.startsWith("vmess://") -> parseVmess(trimmed)
            trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
            trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
            else -> null
        }
    }

    private fun tryDecodeBase64(text: String): String? {
        return try {
            val clean = text.replace("\r", "").replace("\n", "").trim()
            if (clean.length > 20 && !clean.contains("://")) {
                val decodedBytes = Base64.decode(clean, Base64.DEFAULT)
                String(decodedBytes, StandardCharsets.UTF_8)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun parseVless(uri: String): VlessConfig? {
        return try {
            val withoutScheme = uri.removePrefix("vless://")
            val parts = withoutScheme.split("#", limit = 2)
            val main = parts[0]
            val remark = if (parts.size > 1) {
                try { URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) } catch (_: Exception) { parts[1] }
            } else "VLESS Node"

            val atIndex = main.indexOf('@')
            if (atIndex == -1) return null
            val uuid = main.substring(0, atIndex)
            val hostPortQuery = main.substring(atIndex + 1)
            val queryParts = hostPortQuery.split("?", limit = 2)
            val hostPort = queryParts[0].split(":", limit = 2)
            if (hostPort.size < 2) return null

            val address = hostPort[0]
            val port = hostPort[1].toIntOrNull() ?: 443

            val queryMap = mutableMapOf<String, String>()
            if (queryParts.size > 1) {
                queryParts[1].split("&").forEach { pair ->
                    val kv = pair.split("=", limit = 2)
                    if (kv.size == 2) {
                        try {
                            queryMap[kv[0]] = URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name())
                        } catch (_: Exception) {
                            queryMap[kv[0]] = kv[1]
                        }
                    }
                }
            }

            VlessConfig(
                name = remark,
                address = address,
                port = port,
                uuid = uuid,
                protocolType = "vless",
                flow = queryMap["flow"] ?: "xtls-rprx-vision",
                security = queryMap["security"] ?: "reality",
                sni = queryMap["sni"] ?: queryMap["host"] ?: "yandex.ru",
                fingerprint = queryMap["fp"] ?: "chrome",
                publicKey = queryMap["pbk"] ?: "",
                shortId = queryMap["sid"] ?: "",
                remark = remark,
                isFree = true
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTrojan(uri: String): VlessConfig? {
        return try {
            val withoutScheme = uri.removePrefix("trojan://")
            val parts = withoutScheme.split("#", limit = 2)
            val remark = if (parts.size > 1) {
                try { URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) } catch (_: Exception) { parts[1] }
            } else "Trojan Node"
            val main = parts[0]
            val atIndex = main.indexOf('@')
            if (atIndex == -1) return null
            val password = main.substring(0, atIndex)
            val hostPortQuery = main.substring(atIndex + 1)
            val queryParts = hostPortQuery.split("?", limit = 2)
            val hostPort = queryParts[0].split(":", limit = 2)
            val address = hostPort[0]
            val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

            val queryMap = mutableMapOf<String, String>()
            if (queryParts.size > 1) {
                queryParts[1].split("&").forEach { pair ->
                    val kv = pair.split("=", limit = 2)
                    if (kv.size == 2) queryMap[kv[0]] = kv[1]
                }
            }

            VlessConfig(
                name = remark,
                address = address,
                port = port,
                uuid = password,
                protocolType = "trojan",
                security = "tls",
                sni = queryMap["sni"] ?: queryMap["peer"] ?: address,
                fingerprint = "chrome",
                remark = remark,
                isFree = true
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVmess(uri: String): VlessConfig? {
        return try {
            val base64 = uri.removePrefix("vmess://")
            val decoded = String(Base64.decode(base64, Base64.DEFAULT), StandardCharsets.UTF_8)
            val json = JSONObject(decoded)

            val address = json.optString("add", "")
            val port = json.optInt("port", 443)
            val uuid = json.optString("id", "")
            val ps = json.optString("ps", "VMess Node")
            val sni = json.optString("sni", json.optString("host", "yandex.ru"))
            val tls = json.optString("tls", "")

            VlessConfig(
                name = ps,
                address = address,
                port = port,
                uuid = uuid,
                protocolType = "vmess",
                security = if (tls.isNotBlank()) "tls" else "none",
                sni = sni,
                remark = ps,
                isFree = true
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseShadowsocks(uri: String): VlessConfig? {
        return try {
            val withoutScheme = uri.removePrefix("ss://")
            val parts = withoutScheme.split("#", limit = 2)
            val remark = if (parts.size > 1) {
                try { URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) } catch (_: Exception) { parts[1] }
            } else "Shadowsocks Node"
            val main = parts[0]
            
            // Format: user:pass@host:port or base64(user:pass)@host:port
            val atIndex = main.indexOf('@')
            val (address, port, uuid) = if (atIndex != -1) {
                val hostPort = main.substring(atIndex + 1).split(":")
                val userInfo = main.substring(0, atIndex)
                Triple(hostPort[0], hostPort.getOrNull(1)?.toIntOrNull() ?: 8388, userInfo)
            } else {
                val decoded = String(Base64.decode(main, Base64.DEFAULT), StandardCharsets.UTF_8)
                val atIdx = decoded.indexOf('@')
                val hostPort = decoded.substring(atIdx + 1).split(":")
                Triple(hostPort[0], hostPort.getOrNull(1)?.toIntOrNull() ?: 8388, decoded.substring(0, atIdx))
            }

            VlessConfig(
                name = remark,
                address = address,
                port = port,
                uuid = uuid,
                protocolType = "shadowsocks",
                security = "none",
                remark = remark,
                isFree = true
            )
        } catch (e: Exception) {
            null
        }
    }
}
