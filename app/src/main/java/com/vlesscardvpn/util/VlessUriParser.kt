package com.vlesscardvpn.util

import com.vlesscardvpn.domain.VlessConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object VlessUriParser {
    fun parse(uri: String): VlessConfig? {
        return try {
            if (!uri.startsWith("vless://")) return null
            val withoutScheme = uri.removePrefix("vless://")
            val parts = withoutScheme.split("#", limit = 2)
            val main = parts[0]
            val remark = if (parts.size > 1) URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name()) else "Imported"

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
                        queryMap[kv[0]] = URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name())
                    }
                }
            }

            VlessConfig(
                name = remark,
                address = address,
                port = port,
                uuid = uuid,
                flow = queryMap["flow"] ?: "xtls-rprx-vision",
                security = queryMap["security"] ?: "reality",
                sni = queryMap["sni"] ?: "samsung.com",
                fingerprint = queryMap["fp"] ?: "chrome",
                publicKey = queryMap["pbk"] ?: "",
                shortId = queryMap["sid"] ?: "",
                remark = remark
            )
        } catch (e: Exception) {
            null
        }
    }
}