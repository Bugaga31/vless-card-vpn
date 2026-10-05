package com.vlesscardvpn.core

import android.os.ParcelFileDescriptor
import java.io.File

/** hev-socks5-tunnel (JNI): moves packets between the VpnService TUN and the local Xray SOCKS port. */
class TProxyService {
    companion object {
        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
        @JvmStatic external fun TProxyStopService(): Boolean
        @JvmStatic external fun TProxyIsRunning(): Boolean
        @JvmStatic external fun TProxyGetStats(): LongArray?

        init { System.loadLibrary("hev-socks5-tunnel") }

        fun config(socksPort: Int, mtu: Int, ipv4: String, ipv6: String?): String = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $mtu")
            appendLine("  ipv4: $ipv4")
            if (ipv6 != null) appendLine("  ipv6: '$ipv6'")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: warn")
        }

        fun start(dir: File, tun: ParcelFileDescriptor, socksPort: Int, mtu: Int, ipv4: String, ipv6: String?) {
            val f = File(dir, "hev-socks5-tunnel.yaml")
            f.writeText(config(socksPort, mtu, ipv4, ipv6))
            TProxyStartService(f.absolutePath, tun.fd)
        }

        fun stop() { runCatching { TProxyStopService() } }

        /** [tx packets, tx bytes, rx packets, rx bytes] or null. */
        fun stats(): LongArray? = runCatching { TProxyGetStats() }.getOrNull()
    }
}
