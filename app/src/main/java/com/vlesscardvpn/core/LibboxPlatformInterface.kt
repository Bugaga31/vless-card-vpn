package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetInterface
import java.util.Collections

class LibboxPlatformInterface(
    private val vpnService: VpnService,
    private val bypassApps: List<String> = emptyList(),
    private val onTunOpened: (ParcelFileDescriptor) -> Unit
) : PlatformInterface {

    private val connectivityManager = vpnService.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var currentPfd: ParcelFileDescriptor? = null
    private var activeMonitorListener: InterfaceUpdateListener? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override fun autoDetectInterfaceControl(fd: Int) {
        try {
            val protectedOk = vpnService.protect(fd)
            if (!protectedOk) {
                Log.e("LibboxPlatform", "vpnService.protect(fd=$fd) returned false - socket may not be protected!")
            }
        } catch (se: SecurityException) {
            Log.e("LibboxPlatform", "SecurityException during protect(fd=$fd)", se)
            throw se
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
    ): ConnectionOwner {
        // gomobile forwards null as (nil, nil). libbox then dereferences it in Go,
        // which can terminate the process outside Kotlin's exception handlers.
        val owner = NativeCallbackValues.unknownConnectionOwner()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            (ipProtocol != 6 && ipProtocol != 17) ||
            srcIp.isNullOrBlank() || destIp.isNullOrBlank() ||
            srcPort !in 1..65535 || destPort !in 1..65535) return owner

        try {
            val uid = connectivityManager.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(srcIp, srcPort),
                InetSocketAddress(destIp, destPort)
            )
            // Android returns INVALID_UID (-1) if the flow is already gone.
            if (uid >= 0) {
                owner.userId = uid
                owner.androidPackageName = vpnService.packageManager
                    .getPackagesForUid(uid)?.firstOrNull().orEmpty()
            }
        } catch (_: Exception) {
            // A race with VPN revocation or an unavailable owner is not fatal.
            // Keep an explicit unknown UID; do not fabricate root/system UID 0.
        }
        return owner
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<NetworkInterface>()
        try {
            val interfaces = Collections.list(JavaNetInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (iface.isUp) {
                    val addrsList = mutableListOf<String>()
                    // libbox uses netip.MustParsePrefix, NOT ParseAddr. A bare IP
                    // or IPv6 zone identifier causes a Go panic outside Kotlin catches.
                    for (linkAddress in iface.interfaceAddresses) {
                        val address = linkAddress.address ?: continue
                        if (address.isLoopbackAddress) continue
                        InterfacePrefixFormatter.format(address, linkAddress.networkPrefixLength.toInt())
                            ?.let(addrsList::add)
                    }

                    val ni = NetworkInterface().apply {
                        name = iface.name
                        index = iface.index
                        mtu = try { iface.mtu.takeIf { it > 0 } ?: 1500 } catch (_: Exception) { 1500 }
                        flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                        if (iface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                        if (iface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                        if (iface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                        if (iface.interfaceAddresses.any { it.broadcast != null }) {
                            flags = flags or OsConstants.IFF_BROADCAST
                        }
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

                // Route IPv6 through the same core; never silently skip a configured family.
                val inet6Iterator = options.inet6Address
                var hasV6 = false
                while (inet6Iterator != null && inet6Iterator.hasNext()) {
                    val p6 = inet6Iterator.next(); addAddress(p6.address(), p6.prefix()); hasV6 = true
                }
                val route6Iterator = options.inet6RouteAddress
                var hasV6Routes = false
                while (route6Iterator != null && route6Iterator.hasNext()) {
                    val r6 = route6Iterator.next(); addRoute(r6.address(), r6.prefix()); hasV6Routes = true
                }
                if (hasV6 && !hasV6Routes) addRoute("::", 0)

                // DNS
                val dnsBox = options.dnsServerAddress
                if (dnsBox != null && dnsBox.value.isNotBlank()) {
                    addDnsServer(dnsBox.value)
                } else {
                    addDnsServer("1.1.1.1")
                    addDnsServer("8.8.8.8")
                }

                // Disallow self app to prevent VPN routing recursion
                try {
                    addDisallowedApplication(vpnService.packageName)
                } catch (e: Exception) {
                    Log.w("LibboxPlatform", "Could not disallow own package: ${e.message}")
                }

                // Per-app split tunneling: exclude user-selected apps (banks, gov, etc.)
                // at the VpnService level, independent of connection-owner lookup.
                for (pkg in bypassApps) {
                    if (pkg.isBlank() || pkg == vpnService.packageName) continue
                    try {
                        addDisallowedApplication(pkg)
                    } catch (e: Exception) {
                        Log.w("LibboxPlatform", "Bypass app not installed, skipped: $pkg")
                    }
                }
            }

            try { currentPfd?.close() } catch (_: Exception) {}
            val pfd = builder.establish()
            if (pfd == null) {
                Log.e("LibboxPlatform", "Builder.establish() returned null - TUN creation failed (possible permission or resource issue)")
                throw IllegalStateException("VPN builder establish returned null")
            }
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

    override fun systemCertificates(): StringIterator = AndroidTrustAnchors.iterator()

    override fun underNetworkExtension(): Boolean = false

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean = false
}
