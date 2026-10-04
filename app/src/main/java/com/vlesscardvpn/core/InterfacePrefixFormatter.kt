package com.vlesscardvpn.core

import java.net.InetAddress

/** Format the Android interface address as the strict prefix expected by Go netip. */
internal object InterfacePrefixFormatter {
    fun format(address: InetAddress, prefixLength: Int): String? {
        val maxPrefix = when (address.address.size) {
            4 -> 32
            16 -> 128
            else -> return null
        }
        if (prefixLength !in 0..maxPrefix) return null
        val host = address.hostAddress?.substringBefore('%')?.takeIf { it.isNotBlank() } ?: return null
        return "$host/$prefixLength"
    }
}
