package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.VlessConfig
import org.json.JSONArray
import org.json.JSONObject

object SingBoxManager {

    // Domain suffixes for RU Direct split tunneling & DPI bypass (strict suffix matching supported by sing-box)
    private val ruDomainSuffixes = listOf(
        ".ru",
        ".su",
        ".xn--p1ai", // .рф
        "yandex.ru",
        "ya.ru",
        "vk.com",
        "vk.ru",
        "vk-cdn.net",
        "vkvideo.ru",
        "mail.ru",
        "gosuslugi.ru",
        "sberbank.ru",
        "sber.ru",
        "tinkoff.ru",
        "tbank.ru",
        "alfabank.ru",
        "vtb.ru",
        "ozon.ru",
        "wildberries.ru",
        "avito.ru",
        "kinopoisk.ru",
        "dzen.ru",
        "rutube.ru",
        "mos.ru",
        "spb.ru",
        "nalog.gov.ru",
        "cbr.ru",
        "kremlin.ru",
        "customs.gov.ru",
        "pfr.gov.ru",
        "2gis.ru",
        "hh.ru",
        "kinopoisk.ru",
        "pikabu.ru",
        "habr.com"
    )

    // Private / local IPv4 & IPv6 CIDRs
    private val privateIpCidrs = listOf(
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "100.64.0.0/10",
        "::1/128",
        "fc00::/7",
        "fe80::/10"
    )

    /**
     * Resolves the effective SNI for the outbound connection.
     * Preserves explicit SNI from config (including yandex.ru, samsung.com, etc.).
     * Only falls back to custom override or network profile if config SNI is empty.
     */
    fun resolveEffectiveSni(
        config: VlessConfig,
        settings: AppSettings,
        networkProfile: EvaluatedNetworkProfile?
    ): String {
        return when {
            config.sni.isNotBlank() -> config.sni.trim()
            settings.customSniOverride.isNotBlank() && !settings.customSniOverride.equals("auto", ignoreCase = true) -> settings.customSniOverride.trim()
            networkProfile != null && networkProfile.recommendedSni.isNotBlank() -> networkProfile.recommendedSni.trim()
            else -> "yandex.ru"
        }
    }

    /**
     * Generates a fully compliant Sing-Box JSON configuration for the bundled libbox core.
     * Compatible with sing-box 1.8+ / 1.13-mod schema.
     */
    fun generateConfig(
        context: Context?,
        config: VlessConfig,
        settings: AppSettings = AppSettings(),
        networkProfile: EvaluatedNetworkProfile? = null
    ): String {
        val effectiveSni = resolveEffectiveSni(config, settings, networkProfile)
        val effectiveMtu = networkProfile?.optimalMtu ?: settings.mtuSize.coerceIn(1280, 1500)

        val effectiveDns = when {
            settings.customDnsProvider.contains("Google", ignoreCase = true) -> "https://8.8.8.8/dns-query"
            settings.customDnsProvider.contains("Yandex", ignoreCase = true) -> "https://77.88.8.8/dns-query"
            settings.customDnsProvider.contains("Cloudflare", ignoreCase = true) -> "https://1.1.1.1/dns-query"
            networkProfile != null && networkProfile.effectiveDns.isNotBlank() -> networkProfile.effectiveDns
            else -> "https://1.1.1.1/dns-query"
        }

        val proxyOutbound = when (config.protocolType.lowercase()) {
            "vless" -> createVlessOutbound(config, effectiveSni)
            "vmess" -> createVmessOutbound(config, effectiveSni)
            "trojan" -> createTrojanOutbound(config, effectiveSni)
            "shadowsocks", "ss" -> createShadowsocksOutbound(config)
            else -> createVlessOutbound(config, effectiveSni)
        }

        // Rules array with supported sing-box 1.8+ syntax (no pseudo geosite/geoip strings in domain/ip_cidr)
        val rulesArray = JSONArray().apply {
            // 1. DNS Interception
            put(JSONObject().apply {
                put("protocol", "dns")
                put("outbound", "dns-out")
            })

            // 2. Fix YouTube buffering & throttling: Block QUIC (UDP 443, 80) if enabled
            if (settings.blockQuicYouTube) {
                put(JSONObject().apply {
                    put("port", JSONArray(listOf(443, 80)))
                    put("network", "udp")
                    put("outbound", "block")
                })
            }

            // 3. Direct access for local/private IP ranges
            put(JSONObject().apply {
                put("ip_is_private", true)
                put("outbound", "direct")
            })
            put(JSONObject().apply {
                put("ip_cidr", JSONArray(privateIpCidrs))
                put("outbound", "direct")
            })

            // 4. RU Direct Routing (Split Tunneling if enabled)
            if (settings.enableRuDirect) {
                put(JSONObject().apply {
                    put("domain_suffix", JSONArray(ruDomainSuffixes))
                    put("outbound", "direct")
                })
            }
        }

        val route = JSONObject().apply {
            put("rules", rulesArray)
            put("final", "proxy")
            put("auto_detect_interface", true)
        }

        val dnsRulesArray = JSONArray().apply {
            // DNS queries to local/direct routes use local DNS
            if (settings.enableRuDirect) {
                put(JSONObject().apply {
                    put("domain_suffix", JSONArray(ruDomainSuffixes))
                    put("server", "local-dns")
                })
            }
            put(JSONObject().apply {
                put("outbound", "direct")
                put("server", "local-dns")
            })
        }

        val dns = JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "remote-dns")
                    put("address", effectiveDns)
                    put("detour", "proxy")
                })
                put(JSONObject().apply {
                    put("tag", "local-dns")
                    put("address", "77.88.8.8")
                    put("detour", "direct")
                })
            })
            put("rules", dnsRulesArray)
            put("final", "remote-dns")
            put("strategy", "prefer_ipv4")
        }

        val inboundsArray = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("interface_name", "tun0")
                put("inet4_address", "172.19.0.1/30")
                put("mtu", effectiveMtu)
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

    /**
     * Plain TCP VLESS Outbound for sing-box.
     * Note: In sing-box schema, standard TCP transport is default and MUST NOT have `transport: { type: "tcp" }`.
     */
    private fun createVlessOutbound(config: VlessConfig, effectiveSni: String): JSONObject {
        return JSONObject().apply {
            put("type", "vless")
            put("tag", "proxy")
            put("server", config.address.trim())
            put("server_port", config.port)
            put("uuid", config.uuid.trim())
            if (config.flow.isNotBlank()) {
                put("flow", config.flow.trim())
            }
            if (config.security.equals("reality", ignoreCase = true) || config.security.equals("tls", ignoreCase = true)) {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    put("server_name", effectiveSni)
                    put("insecure", false)
                    put("utls", JSONObject().apply {
                        put("enabled", true)
                        put("fingerprint", config.fingerprint.ifBlank { "chrome" })
                    })
                    if (config.security.equals("reality", ignoreCase = true)) {
                        put("reality", JSONObject().apply {
                            put("enabled", true)
                            put("public_key", config.publicKey.trim())
                            put("short_id", config.shortId.trim())
                        })
                    }
                })
            }
        }
    }

    private fun createVmessOutbound(config: VlessConfig, effectiveSni: String): JSONObject {
        return JSONObject().apply {
            put("type", "vmess")
            put("tag", "proxy")
            put("server", config.address.trim())
            put("server_port", config.port)
            put("uuid", config.uuid.trim())
            put("security", "auto")
            if (config.security.equals("tls", ignoreCase = true)) {
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
            put("server", config.address.trim())
            put("server_port", config.port)
            put("password", config.uuid.trim())
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
            put("server", config.address.trim())
            put("server_port", config.port)
            put("method", method.trim())
            put("password", password.trim())
        }
    }

    /**
     * Validates that the generated JSON config has all required keys, valid outbounds,
     * and valid rule structure according to sing-box schema.
     */
    fun validateGeneratedConfig(jsonString: String): Result<Unit> {
        return try {
            val root = JSONObject(jsonString)
            if (!root.has("inbounds") || root.getJSONArray("inbounds").length() == 0) {
                return Result.failure(IllegalArgumentException("Missing inbounds in generated config"))
            }
            if (!root.has("outbounds") || root.getJSONArray("outbounds").length() == 0) {
                return Result.failure(IllegalArgumentException("Missing outbounds in generated config"))
            }
            if (!root.has("route")) {
                return Result.failure(IllegalArgumentException("Missing route in generated config"))
            }
            if (!root.has("dns")) {
                return Result.failure(IllegalArgumentException("Missing dns in generated config"))
            }

            // Check proxy outbound
            val outbounds = root.getJSONArray("outbounds")
            var foundProxy = false
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.getJSONObject(i)
                if (ob.optString("tag") == "proxy") {
                    foundProxy = true
                    if (ob.has("transport") && ob.getJSONObject("transport").optString("type") == "tcp") {
                        return Result.failure(IllegalArgumentException("Invalid transport type tcp in sing-box outbound"))
                    }
                }
            }
            if (!foundProxy) {
                return Result.failure(IllegalArgumentException("No proxy tag found in outbounds"))
            }

            // Check route rules for invalid geosite/geoip in domain/ip_cidr
            val route = root.getJSONObject("route")
            val rules = route.getJSONArray("rules")
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                if (rule.has("domain")) {
                    val doms = rule.getJSONArray("domain")
                    for (j in 0 until doms.length()) {
                        val d = doms.getString(j)
                        if (d.startsWith("geosite:") || d.startsWith("domain:")) {
                            return Result.failure(IllegalArgumentException("Illegal pseudo-domain $d in rule domain array"))
                        }
                    }
                }
                if (rule.has("ip_cidr")) {
                    val ips = rule.getJSONArray("ip_cidr")
                    for (j in 0 until ips.length()) {
                        val ip = ips.getString(j)
                        if (ip.startsWith("geoip:")) {
                            return Result.failure(IllegalArgumentException("Illegal pseudo-geoip $ip in rule ip_cidr array"))
                        }
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
