package com.vlesscardvpn.domain

import com.vlesscardvpn.util.UniversalConfigParser
import java.security.MessageDigest
import java.util.UUID

/** Small real compatibility cascade; no fabricated fake-packet or random-SNI modes. */
object AutoConnectPolicy {
    const val MAX_ATTEMPTS = 12
    fun identity(c: VlessConfig): String {
        val raw = listOf(c.protocolType.lowercase(), c.address.lowercase(), c.port.toString(), c.uuid,
            c.security.lowercase(), c.sni.lowercase(), c.publicKey, c.shortId, c.flow, c.fingerprint,
            c.transport.lowercase(), c.wsHost, c.wsPath, c.serviceName).joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun supports(c: VlessConfig): Boolean {
        if (c.address.isBlank() || c.address.any { it.isWhitespace() || it == '/' || it == '@' } || c.port !in 1..65535 || c.uuid.isBlank()) return false
        if (c.transport.lowercase() !in setOf("tcp", "ws", "websocket", "grpc", "gun", "h2", "http2")) return false
        val protocol = c.protocolType.lowercase()
        if (protocol !in setOf("vless", "vmess", "trojan", "ss", "shadowsocks")) return false
        if (protocol == "ss" || protocol == "shadowsocks") {
            val method = c.uuid.substringBefore(':').lowercase()
            if (method !in setOf("aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305",
                "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305") ||
                !c.uuid.contains(':') || c.uuid.substringAfter(':').isBlank()) return false
        }
        if (protocol == "vless" || protocol == "vmess") {
            if (runCatching { UUID.fromString(c.uuid).toString().equals(c.uuid, true) }.getOrDefault(false).not()) return false
            if (c.security.lowercase() !in setOf("tls", "reality")) return false
        }
        if (c.security.equals("reality", true)) {
            if (protocol != "vless" || c.sni.isBlank()) return false
            if (UniversalConfigParser.decodeBase64Safe(c.publicKey)?.size != 32) return false
            if (c.shortId.length > 16 || c.shortId.length % 2 != 0 || !c.shortId.all { it in "0123456789abcdefABCDEF" }) return false
        }
        if (c.flow.isNotBlank() && protocol == "vless" && (c.flow != "xtls-rprx-vision" || c.transport != "tcp")) return false
        return true
    }
    fun rank(configs: List<VlessConfig>, favoritesOnly: Boolean = false): List<VlessConfig> = configs
        .filter { supports(it) && (!favoritesOnly || it.isFavorite) }.distinctBy(::identity)
        .sortedWith(compareByDescending<VlessConfig> { it.isFavorite }
            .thenByDescending { !it.isFree }
            .thenBy { if (it.security.equals("reality", true)) 0 else 1 }
            .thenBy { if (it.pingMs > 0) it.pingMs else Int.MAX_VALUE })
    fun settings(base: AppSettings, fragment: Boolean): AppSettings = base.copy(
        evasionStrategy = if (fragment) "tls_fragment" else "stable_tls",
        enableSniRotation = false, customSniOverride = "auto", enableFragmentation = fragment,
        enableRuDirect = false, enableAdBlock = false, bypassApps = emptyList(), enableTcpFastOpen = false
    )
    fun canFragment(c: VlessConfig): Boolean = c.protocolType.lowercase() in setOf("vless", "vmess", "trojan") &&
        (c.security.equals("tls", true) || c.security.equals("reality", true))
}
