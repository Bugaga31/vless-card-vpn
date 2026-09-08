package com.vlesscardvpn.core

import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.VlessConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates configuration for v2ray-core (libv2ray.aar) with support for
 * VLESS, VMess, Trojan, Shadowsocks, WebSocket, gRPC, and Anti-DPI TLS disguise.
 */
object V2RayManager {

    fun generateConfig(
        config: VlessConfig,
        socksPort: Int,
        settings: AppSettings = AppSettings()
    ): String {
        val effectiveSni = if (config.sni.isNotBlank()) {
            config.sni.trim()
        } else if (settings.enableSniRotation) {
            SniPool.randomSni()
        } else if (settings.customSniOverride.isNotBlank() && !settings.customSniOverride.equals("auto", ignoreCase = true)) {
            settings.customSniOverride.trim()
        } else {
            "yandex.ru"
        }

        val streamSettings = JSONObject().apply {
            val net = when (config.transport.lowercase()) {
                "ws", "websocket" -> "ws"
                "grpc" -> "grpc"
                "h2", "http" -> "h2"
                else -> "tcp"
            }
            put("network", net)

            // Security: tls or none
            val sec = if (config.security.equals("tls", ignoreCase = true) || config.security.equals("reality", ignoreCase = true)) {
                "tls"
            } else {
                "none"
            }
            put("security", sec)

            if (sec == "tls") {
                val tlsObj = JSONObject().apply {
                    put("serverName", effectiveSni)
                    put("allowInsecure", false)
                    val alpnList = JSONArray().apply {
                        put("h2")
                        put("http/1.1")
                    }
                    put("alpn", alpnList)
                }
                put("tlsSettings", tlsObj)
            }

            // Transport details
            when (net) {
                "ws" -> {
                    put("wsSettings", JSONObject().apply {
                        put("path", config.wsPath.ifBlank { "/" })
                        val host = config.wsHost.ifBlank { effectiveSni }
                        if (host.isNotBlank()) {
                            put("headers", JSONObject().apply {
                                put("Host", host)
                            })
                        }
                    })
                }
                "grpc" -> {
                    put("grpcSettings", JSONObject().apply {
                        put("serviceName", config.serviceName.ifBlank { config.wsPath.trimStart('/') })
                        put("multiMode", false)
                    })
                }
                "tcp" -> {
                    // TCP Header disguise (HTTP spoofing for DPI evasion)
                    if (settings.customSniOverride.contains("yandex", ignoreCase = true) || config.sni.contains("yandex", ignoreCase = true)) {
                        put("tcpSettings", JSONObject().apply {
                            put("header", JSONObject().apply {
                                put("type", "none")
                            })
                        })
                    }
                }
            }
        }

        // Build outbound proxy protocol
        val outbound = JSONObject().apply {
            put("tag", "proxy")
            put("streamSettings", streamSettings)
            put("mux", JSONObject().apply { put("enabled", false) })

            when (config.protocolType.lowercase()) {
                "vless" -> {
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        val users = JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", config.uuid)
                                put("encryption", "none")
                                if (config.flow.isNotBlank() && !config.flow.contains("vision", ignoreCase = true)) {
                                    put("flow", config.flow)
                                }
                            })
                        }
                        put("vnext", JSONArray().apply {
                            put(JSONObject().apply {
                                put("address", config.address)
                                put("port", config.port)
                                put("users", users)
                            })
                        })
                    })
                }
                "vmess" -> {
                    put("protocol", "vmess")
                    put("settings", JSONObject().apply {
                        val users = JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", config.uuid)
                                put("alterId", 0)
                                put("security", "auto")
                            })
                        }
                        put("vnext", JSONArray().apply {
                            put(JSONObject().apply {
                                put("address", config.address)
                                put("port", config.port)
                                put("users", users)
                            })
                        })
                    })
                }
                "trojan" -> {
                    put("protocol", "trojan")
                    put("settings", JSONObject().apply {
                        val servers = JSONArray().apply {
                            put(JSONObject().apply {
                                put("address", config.address)
                                put("port", config.port)
                                put("password", config.uuid)
                            })
                        }
                        put("servers", servers)
                    })
                }
                "shadowsocks", "ss" -> {
                    put("protocol", "shadowsocks")
                    put("settings", JSONObject().apply {
                        val servers = JSONArray().apply {
                            put(JSONObject().apply {
                                put("address", config.address)
                                put("port", config.port)
                                put("method", "aes-128-gcm")
                                put("password", config.uuid)
                            })
                        }
                        put("servers", servers)
                    })
                }
                else -> {
                    // Default fallback: vless
                    put("protocol", "vless")
                    put("settings", JSONObject().apply {
                        val users = JSONArray().apply {
                            put(JSONObject().apply {
                                put("id", config.uuid)
                                put("encryption", "none")
                            })
                        }
                        put("vnext", JSONArray().apply {
                            put(JSONObject().apply {
                                put("address", config.address)
                                put("port", config.port)
                                put("users", users)
                            })
                        })
                    })
                }
            }
        }

        val directOutbound = JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            put("settings", JSONObject().apply {
                put("domainStrategy", "UseIP")
            })
        }

        val blockOutbound = JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "none") })
            })
        }

        // Full V2Ray config object
        return JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            put("stats", JSONObject())
            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "socks-in")
                    put("listen", "127.0.0.1")
                    put("port", socksPort)
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                    put("sniffing", JSONObject().apply {
                        put("enabled", true)
                        put("destOverride", JSONArray().apply {
                            put("http")
                            put("tls")
                        })
                    })
                })
            })
            put("outbounds", JSONArray().apply {
                put(outbound)
                put(directOutbound)
                put(blockOutbound)
            })
            put("routing", JSONObject().apply {
                put("domainStrategy", "IPIfNonMatch")
                put("rules", JSONArray().apply {
                    if (settings.enableRuDirect) {
                        put(JSONObject().apply {
                            put("type", "field")
                            put("outboundTag", "direct")
                            put("domain", JSONArray().apply {
                                put("domain:ru")
                                put("domain:su")
                                put("domain:xn--p1ai")
                                put("domain:yandex.ru")
                                put("domain:vk.com")
                                put("domain:gosuslugi.ru")
                                put("domain:sberbank.ru")
                                put("domain:tbank.ru")
                            })
                        })
                    }
                })
            })
        }.toString()
    }
}
