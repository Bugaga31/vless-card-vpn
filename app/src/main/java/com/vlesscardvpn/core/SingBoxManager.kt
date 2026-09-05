package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.domain.VlessConfig
import org.json.JSONArray
import org.json.JSONObject

object SingBoxManager {
    // Hardcoded split routing rules for VLESS Reality
    private val splitRules = listOf(
        "geosite:cn",
        "geosite:ru",
        "geoip:cn",
        "geoip:ru"
    )

    fun generateConfig(context: Context, config: VlessConfig): String {
        val networkSni = NetworkMonitor.getSniForNetwork(context)
        // Override SNI for masking based on network
        val effectiveSni = if (config.sni.isNotBlank() && config.sni != "samsung.com" && config.sni != "yandex.ru") {
            config.sni
        } else networkSni

        val outbound = JSONObject().apply {
            put("type", "vless")
            put("tag", "proxy")
            put("server", config.address)
            put("server_port", config.port)
            put("uuid", config.uuid)
            put("flow", config.flow)
            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", effectiveSni)
                put("insecure", false)
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", config.fingerprint)
                })
                put("reality", JSONObject().apply {
                    put("enabled", true)
                    put("public_key", config.publicKey.ifBlank { "publickey-placeholder" })
                    put("short_id", config.shortId.ifBlank { "00" })
                })
            })
            put("transport", JSONObject().apply {
                put("type", "tcp")
            })
        }

        val route = JSONObject().apply {
            put("rules", JSONArray().apply {
                add(JSONObject().apply {
                    put("outbound", "direct")
                    put("domain", JSONArray(splitRules))
                })
                add(JSONObject().apply {
                    put("outbound", "proxy")
                    put("domain", JSONArray(listOf("geosite:category-ads-all")))
                    put("invert", true)
                })
            })
            put("final", "proxy")
        }

        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                add(JSONObject().apply { put("address", "1.1.1.1"); put("tag", "remote") })
                add(JSONObject().apply { put("address", "8.8.8.8"); put("tag", "local") })
            })
            put("rules", JSONArray().apply {
                add(JSONObject().apply { put("outbound", "direct"); put("server", "local") })
            })
            put("final", "remote")
        }

        val fullConfig = JSONObject().apply {
            put("log", JSONObject().apply { put("level", "warn") })
            put("inbounds", JSONArray().apply {
                add(JSONObject().apply {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("interface_name", "tun0")
                    put("inet4_address", "172.19.0.1/30")
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "system")
                    put("sniff", true)
                })
            })
            put("outbounds", JSONArray().apply {
                add(outbound)
                add(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
                add(JSONObject().apply { put("type", "block"); put("tag", "block") })
            })
            put("route", route)
            put("dns", dns)
        }

        return fullConfig.toString(2)
    }
}