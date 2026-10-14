package com.vlesscardvpn.core

/** Compatibility-oriented virtual TUN MTU, not a physical path-MTU measurement or speed guarantee. */
object TransportPolicy {
    fun mtu(cellular: Boolean, warp: Boolean, linkMtu: Int = 0): Int {
        val target = if (warp) 1280 else if (cellular) 1360 else 1400
        return if (linkMtu in 1280..1399) minOf(target, linkMtu) else target
    }
    fun animateStatus(busy: Boolean, foreground: Boolean, allowLight: Boolean) = busy && foreground && allowLight
    fun sniff(quicAllowed: Boolean) = if (quicAllowed) listOf("http", "tls", "quic") else listOf("http", "tls")
}
