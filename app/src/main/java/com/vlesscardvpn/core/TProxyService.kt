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

        fun config(socksPort: Int, mtu: Int, ipv4: String, ipv6: String?, user: String = "", pass: String = "", quietLogs: Boolean = false): String = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $mtu")
            appendLine("  ipv4: $ipv4")
            if (ipv6 != null) appendLine("  ipv6: '$ipv6'")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            if (user.isNotEmpty()) { appendLine("  username: '$user'"); appendLine("  password: '$pass'") }
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: ${if (quietLogs) "error" else "warn"}")
        }

        fun start(dir: File, tun: ParcelFileDescriptor, socksPort: Int, mtu: Int, ipv4: String, ipv6: String?, user: String = "", pass: String = "", quietLogs: Boolean = false) {
            val f = File(dir, "hev-socks5-tunnel.yaml")
            f.writeText(config(socksPort, mtu, ipv4, ipv6, user, pass, quietLogs))
            check(TProxyStartService(f.absolutePath, tun.fd)) { "Не удалось запустить мост TUN–SOCKS" }
        }

        /** Restart the userspace bridge only; the VpnService still owns and keeps its TUN fd.
         * Reuse the established interface config and SOCKS credentials, not newly guessed values.
         * Native tunnel_fini does not close externally supplied descriptors.
         */
        fun resume(dir: File, tun: ParcelFileDescriptor) {
            val f = File(dir, "hev-socks5-tunnel.yaml")
            check(f.isFile) { "Нет конфигурации моста TUN–SOCKS" }
            check(TProxyStartService(f.absolutePath, tun.fd)) { "Не удалось восстановить мост TUN–SOCKS" }
        }

        fun stop() { runCatching { TProxyStopService() } }

        /** [tx packets, tx bytes, rx packets, rx bytes] or null. */
        fun stats(): LongArray? = runCatching { TProxyGetStats() }.getOrNull()
    }
}
