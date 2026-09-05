package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.VlessConfig
import org.json.JSONArray
import org.json.JSONObject

object SingBoxManager {

    private val ruSplitDomains = listOf(
        "geosite:ru",
        "geosite:category-ru",
        "geosite:yandex",
        "geosite:vk",
        "geosite:mailru",
        "geosite:sberbank",
        "geosite:gosuslugi",
        "geosite:tinkoff",
        "geosite:ozon",
        "geosite:wildberries",
        "geosite:avito",
        "domain:ru",
        "domain:su",
        "domain:xn--p1ai"
    )

    private val ruSplitIps = listOf(
        "geoip:ru",
        "geoip:private"
    )

    fun generateConfig(
        context: Context,
        config: VlessConfig,
        settings: AppSettings = AppSettings()
    ): String {
        // SNI / Masking logic: If user enabled custom masking, use it, otherwise use config SNI or smart network SNI
        val effectiveSni = when {
            settings.customSniOverride.isNotBlank() && settings.customSniOverride != "auto" -> settings.customSniOverride
            config.sni.isNotBlank() && config.sni != "samsung.com" && config.sni != "yandex.ru" -> config.sni
            else -> NetworkMonitor.getSniForNetwork(context)
        }

        val proxyOutbound = when (config.protocolType.lowercase()) {
            "vless" -> createVlessOutbound(config, effectiveSni)
            "vmess" -> createVmessOutbound(config, effectiveSni)
            "trojan" -> createTrojanOutbound(config, effectiveSni)
            "shadowsocks" -> createShadowsocksOutbound(config)
            else -> createVlessOutbound(config, effectiveSni)
        }

        val rulesArray = JSONArray().apply {
            // DNS hijack
            put(JSONObject().apply {
                put("protocol", "dns")
                put("outbound", "dns-out")
            })

            // RU Direct Routing (if enabled)
            if (settings.enableRuDirect) {
                put(JSONObject().apply {
                    put("outbound", "direct")
                    put("domain", JSONArray(ruSplitDomains))
                })
                put(JSONObject().apply {
                    put("outbound", "direct")
                    put("ip_cidr", JSONArray(ruSplitIps))
                })
            }

            // Ads blocking
            put(JSONObject().apply {
                put("outbound", "block")
                put("domain", JSONArray(listOf("geosite:category-ads-all")))
            })
        }

        val route = JSONObject().apply {
            put("rules", rulesArray)
            put("final", "proxy")
            put("auto_detect_interface", true)
        }

        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "remote-dns")
                    put("address", "https://1.1.1.1/dns-query")
                    put("detour", "proxy")
                })
                put(JSONObject().apply {
                    put("tag", "local-dns")
                    put("address", "77.88.8.8")
                    put("detour", "direct")
                })
            })
            put("rules", JSONArray().apply {
                if (settings.enableRuDirect) {
                    put(JSONObject().apply {
                        put("domain", JSONArray(ruSplitDomains))
                        put("server", "local-dns")
                    })
                }
                put(JSONObject().apply {
                    put("outbound", "direct")
                    put("server", "local-dns")
                })
            })
            put("final", "remote-dns")
            put("strategy", "prefer_ipv4")
        }

        val inboundsArray = JSONArray().apply {
            put(JSONObject().apply {
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
        }

        val outboundsArray = JSONArray().apply {
            put(proxyOutbound)
            put(JSONObject().apply { put("type", "direct"); put("tag", "direct") })
            put(JSONObject().apply { put("type", "block"); put("tag", "block") })
            put(JSONObject().apply { put("type", "dns"); put("tag", "dns-out") })
        }

        val root = JSONObject().apply {
            put("log", JSONObject().apply {
                put("level", "warn")
                put("timestamp", true)
            })
            put("dns", dns)
            put("inbounds", inboundsArray)
            put("outbounds", outboundsArray)
            put("route", route)
        }

        return root.toString(2)
    }

    private fun createVlessOutbound(config: VlessConfig, effectiveSni: String): JSONObject {
        return JSONObject().apply {
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
                    put("fingerprint", config.fingerprint.ifBlank { "chrome" })
                })
                if (config.security == "reality") {
                    put("reality", JSONObject().apply {
                        put("enabled", true)
                        put("public_key", config.publicKey.ifBlank { "k8b3h..." })
                        put("short_id", config.shortId.ifBlank { "" })
                    })
                }
            })
            put("transport", JSONObject().apply {
                put("type", "tcp")
            })
        }
    }

    private fun createVmessOutbound(config: VlessConfig, effectiveSni: String): JSONObject {
        return JSONObject().apply {
            put("type", "vmess")
            put("tag", "proxy")
            put("server", config.address)
            put("server_port", config.port)
            put("uuid", config.uuid)
            put("security", "auto")
            if (config.security == "tls") {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", effectiveSni)
                })
            }
        }
    }

    private fun createTrojanOutbound(config: VlessConfig, effectiveSni: String): JSONObject {
        return JSONObject().apply {
            put("type", "trojan")
            put("tag", "proxy")
            put("server", config.address)
            put("server_port", config.port)
            put("password", config.uuid)
            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", effectiveSni)
            })
        }
    }

    private fun createShadowsocksOutbound(config: VlessConfig): JSONObject {
        val parts = config.uuid.split(":", limit = 2)
        val method = parts.getOrNull(0) ?: "aes-256-gcm"
        val password = parts.getOrNull(1) ?: config.uuid

        return JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", "proxy")
            put("server", config.address)
            put("server_port", config.port)
            put("method", method)
            put("password", password)
        }
    }
}
