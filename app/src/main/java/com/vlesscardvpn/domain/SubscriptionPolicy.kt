package com.vlesscardvpn.domain

import java.net.URI
import java.net.InetAddress

object SubscriptionPolicy {
    const val MAX_TEXT = 2 * 1024 * 1024
    fun url(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null && uri.fragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        require(!uri.host.equals("localhost", true) && !uri.host.endsWith(".local", true) && !uri.host.endsWith(".localhost", true))
        if (uri.host.contains(':') || uri.host.matches(Regex("[0-9.]+"))) require(publicAddress(InetAddress.getByName(uri.host)))
        uri.toASCIIString()
    }.getOrNull()
    fun publicAddress(ip: InetAddress): Boolean = !(ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress ||
        ip.isSiteLocalAddress || ip.isMulticastAddress || ip.address.size == 16 && (ip.address[0].toInt() and 0xfe) == 0xfc)
    fun urls(text: String): List<String> {
        require(text.length <= MAX_TEXT) { "Импорт больше 2 МиБ" }
        return text.lineSequence().mapNotNull { url(it) }.distinct().take(12).toList()
    }
}
