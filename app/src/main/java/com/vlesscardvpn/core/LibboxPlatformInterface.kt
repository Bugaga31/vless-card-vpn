package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.*
import java.io.File
import java.net.NetworkInterface as JavaNetInterface
import java.util.Collections

class LibboxPlatformInterface(
    private val vpnService: VpnService,
    private val onTunOpened: (ParcelFileDescriptor) -> Unit
) : PlatformInterface {

    private var currentPfd: ParcelFileDescriptor? = null

    override fun autoDetectInterfaceControl(fd: Int) {
        vpnService.protect(fd)
    }

    override fun clearDNSCache() {
        // System DNS cache cleared if platform allows
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        // No-op for default simple mobile listener
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        srcIp: String?,
        srcPort: Int,
        destIp: String?,
        destPort: Int
    ): ConnectionOwner? {
        return null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<NetworkInterface>()
        try {
            val interfaces = Collections.list(JavaNetInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (iface.isUp) {
                    val ni = NetworkInterface().apply {
                        name = iface.name
                        index = iface.index
                        mtu = try { iface.mtu } catch (_: Exception) { 1500 }
                    }
                    list.add(ni)
                }
            }
        } catch (e: Exception) {
            Log.e("LibboxPlatform", "Error getting interfaces", e)
        }
        return object : NetworkInterfaceIterator {
            private var idx = 0
            override fun hasNext(): Boolean = idx < list.size
            override fun next(): NetworkInterface = list[idx++]
        }
    }

    override fun includeAllNetworks(): Boolean = false

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun openTun(options: TunOptions): Int {
        try {
            val builder = vpnService.Builder().apply {
                setSession("VLESS Stealth Core")
                setMtu(if (options.mtu > 0) options.mtu else 1400)
                setBlocking(false)

                // Configure IPv4
                val inet4Iterator = options.inet4Address
                var hasV4 = false
                while (inet4Iterator != null && inet4Iterator.hasNext()) {
                    val prefix = inet4Iterator.next()
                    addAddress(prefix.address(), prefix.prefix())
                    hasV4 = true
                }
                if (!hasV4) {
                    addAddress("172.19.0.1", 30)
                }

                // Add Routes
                val routeIterator = options.inet4RouteAddress
                var hasRoutes = false
                while (routeIterator != null && routeIterator.hasNext()) {
                    val r = routeIterator.next()
                    addRoute(r.address(), r.prefix())
                    hasRoutes = true
                }
                if (!hasRoutes) {
                    addRoute("0.0.0.0", 0)
                }

                // DNS
                val dnsBox = options.dnsServerAddress
                if (dnsBox != null && dnsBox.value.isNotBlank()) {
                    addDnsServer(dnsBox.value)
                } else {
                    addDnsServer("1.1.1.1")
                    addDnsServer("8.8.8.8")
                }
            }

            currentPfd?.close()
            val pfd = builder.establish() ?: throw IllegalStateException("VPN builder establish returned null")
            currentPfd = pfd
            onTunOpened(pfd)
            return pfd.fd
        } catch (e: Exception) {
            Log.e("LibboxPlatform", "Failed to open TUN interface", e)
            throw e
        }
    }

    override fun readWIFIState(): WIFIState {
        return Libbox.newWIFIState("", "")
    }

    override fun sendNotification(notification: Notification?) {
        // Notification handled by VpnService foreground
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        // Default monitor start
    }

    override fun systemCertificates(): StringIterator {
        return object : StringIterator {
            override fun hasNext(): Boolean = false
            override fun len(): Int = 0
            override fun next(): String = ""
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean = false
}
