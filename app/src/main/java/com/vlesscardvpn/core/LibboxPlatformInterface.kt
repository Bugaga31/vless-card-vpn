package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface as JavaNetInterface
import java.util.Collections

class LibboxPlatformInterface(
    private val vpnService: VpnService,
    private val onTunOpened: (ParcelFileDescriptor) -> Unit
) : PlatformInterface {

    private val connectivityManager = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var currentPfd: ParcelFileDescriptor? = null
    private var activeMonitorListener: InterfaceUpdateListener? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override fun autoDetectInterfaceControl(fd: Int) {
        val protectedOk = vpnService.protect(fd)
        if (!protectedOk) {
            Log.e("LibboxPlatform", "vpnService.protect(fd=$fd) returned false!")
        }
    }

    override fun clearDNSCache() {
        // System DNS cache cleared if platform allows
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (listener == null) return
        activeMonitorListener = listener

        try {
            // Unregister any previous monitor
            defaultNetworkCallback?.let {
                try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    reportNetworkUpdate(network)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    reportNetworkUpdate(network)
                }

                override fun onLost(network: Network) {
                    activeMonitorListener?.updateDefaultInterface("", -1, false, false)
                }
            }

            defaultNetworkCallback = callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                val req = android.net.NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(req, callback)
            }

            // Immediately report current active network if available
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                reportNetworkUpdate(activeNetwork)
            }
        } catch (e: Exception) {
            Log.e("LibboxPlatform", "Failed to start default interface monitor", e)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        try {
            defaultNetworkCallback?.let {
                connectivityManager.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            Log.w("LibboxPlatform", "Error unregistering network callback: ${e.message}")
        } finally {
            defaultNetworkCallback = null
            activeMonitorListener = null
        }
    }

    private fun reportNetworkUpdate(network: Network) {
        val listener = activeMonitorListener ?: return
        try {
            val lp = connectivityManager.getLinkProperties(network) ?: return
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return

            // Ensure this is not our own VPN network interface to avoid feedback loops
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return

            val ifaceName = lp.interfaceName ?: ""
            var ifaceIndex = -1
            var hasV4 = false
            var hasV6 = false

            for (linkAddr in lp.linkAddresses) {
                val addr = linkAddr.address
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    hasV4 = true
                } else if (addr is Inet6Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    hasV6 = true
                }
            }

            if (ifaceName.isNotBlank()) {
                try {
                    val jni = JavaNetInterface.getByName(ifaceName)
                    if (jni != null) {
                        ifaceIndex = jni.index
                    }
                } catch (_: Exception) {}
            }

            listener.updateDefaultInterface(ifaceName, ifaceIndex, hasV4, hasV6)
        } catch (e: Exception) {
            Log.e("LibboxPlatform", "Error reporting network update", e)
        }
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
                    val addrsList = mutableListOf<String>()
                    for (addr in Collections.list(iface.inetAddresses)) {
                        if (!addr.isLoopbackAddress) {
                            addrsList.add(addr.hostAddress ?: "")
                        }
                    }

                    val ni = NetworkInterface().apply {
                        name = iface.name
                        index = iface.index
                        mtu = try { iface.mtu } catch (_: Exception) { 1500 }
                        addresses = object : StringIterator {
                            private var aIdx = 0
                            override fun hasNext(): Boolean = aIdx < addrsList.size
                            override fun len(): Int = addrsList.size
                            override fun next(): String = addrsList[aIdx++]
                        }
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
                val mtu = if (options.mtu in 1280..1500) options.mtu else 1400
                setMtu(mtu)
                setBlocking(false)

                // Configure IPv4 Address
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

                // Add IPv4 Routes
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

                // Configure IPv6: support if configured in TunOptions, otherwise exclude/do not route
                val inet6Iterator = options.inet6Address
                while (inet6Iterator != null && inet6Iterator.hasNext()) {
                    val p6 = inet6Iterator.next()
                    try {
                        addAddress(p6.address(), p6.prefix())
                    } catch (e: Exception) {
                        Log.w("LibboxPlatform", "IPv6 address add skipped: ${e.message}")
                    }
                }

                val route6Iterator = options.inet6RouteAddress
                while (route6Iterator != null && route6Iterator.hasNext()) {
                    val r6 = route6Iterator.next()
                    try {
                        addRoute(r6.address(), r6.prefix())
                    } catch (e: Exception) {
                        Log.w("LibboxPlatform", "IPv6 route add skipped: ${e.message}")
                    }
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

            try { currentPfd?.close() } catch (_: Exception) {}
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
        // Foreground notification handled by VlessVpnService
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
