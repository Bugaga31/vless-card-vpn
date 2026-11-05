package com.vlesscardvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager

/** Name of the current underlying network ("Моб.: Beeline", "Wi-Fi"): DPI strategies are remembered per network. */
object Net {
    fun underlying(context: Context): android.net.Network? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val current = cm.getNetworkCapabilities(active)
        if (current != null && !current.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) active
        else cm.allNetworks.filter { n -> cm.getNetworkCapabilities(n)?.let {
            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true }.sortedByDescending { n ->
            val caps = cm.getNetworkCapabilities(n)!!
            (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                (if (current != null && listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
                    NetworkCapabilities.TRANSPORT_ETHERNET).any { caps.hasTransport(it) && current.hasTransport(it) }) 1 else 0)
        }.firstOrNull()
    }.getOrNull()
    fun observation(context:Context):NetworkHandoff.Snapshot? = underlying(context)?.let {
        NetworkHandoff.Snapshot(it.toString(),key(context,it),pathRevision(context,it))
    }
    fun matches(context:Context,start:NetworkHandoff.Snapshot?) = NetworkObservation.accepts(start,observation(context))
    /** 1.2.136: [NetworkPath.transport] — address lifetimes, DNS, MTU and IPv6 temporary-address rotation
     * on the same link no longer count as a new path (each used to rebuild the route). */
    fun pathRevision(context:Context,network:android.net.Network):String = runCatching {
        val links=context.getSystemService(ConnectivityManager::class.java).getLinkProperties(network) ?: return@runCatching ""
        NetworkPath.transport(links.interfaceName.orEmpty(),
            links.linkAddresses.mapNotNull { a -> a.address?.hostAddress?.let { it+"/"+a.prefixLength } },
            links.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress })
    }.getOrDefault("")
    fun key(context: Context, network: android.net.Network? = underlying(context)): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(network)
        val handle = network?.toString().orEmpty()
        when {
            caps == null -> "Нет сети"
            // Each Wi-Fi has its own DPI (home provider ≠ café ≠ work): told apart by the router address (no location permission needed).
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val link = cm.getLinkProperties(network)
                val gateway = link?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress.orEmpty()
                // SSID is OPTIONAL: when Android redacts it, do not request new location permissions.
                val ssid = runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.ssid }
                    .getOrNull()?.takeUnless { it == "<unknown ssid>" || it == "\"<unknown ssid>\"" }.orEmpty()
                val dns = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.sorted().joinToString(",")
                NetworkIdentity.session.stable(handle, wifiKey(salt(context), ssid, gateway, dns, network.toString()),
                    NetworkIdentity.wifiRank(gateway, ssid))
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = context.getSystemService(TelephonyManager::class.java)
                val visible = tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm?.simOperatorName?.takeIf { it.isNotBlank() }
                val name = visible ?: "оператор"
                val code = runCatching { tm?.networkOperator }.getOrNull().orEmpty().takeIf { it.matches(Regex("[0-9]{5,6}")) }
                NetworkIdentity.session.stable(handle, "Моб.: " + name + " · CELL_" + (code ?: fingerprint(salt(context), name)),
                    NetworkIdentity.cellularRank(visible.orEmpty(), code))
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Другая сеть"
        }
    }.getOrDefault("Сеть")

    /** Hidden SSIDs + identical router/DNS must not merge unrelated Wi-Fi sessions.
     * The fallback is deliberately session-scoped; no location permission or raw identifiers are stored.
     */
    fun wifiKey(salt: String, ssid: String, gateway: String, dns: String, session: String): String {
        val visible = ssid.trim().removeSurrounding("\"").takeUnless { it.isBlank() || it == "<unknown ssid>" || it == "0x" }.orEmpty()
        return "Wi-Fi · WIFI_" + fingerprint(salt, visible, gateway, dns, if (visible.isEmpty()) session else "")
    }

    /** Local salted identifier; raw SSID/gateway never becomes the persisted profile key. */
    fun fingerprint(salt: String, vararg fields: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((salt + "|" + fields.joinToString("|")).toByteArray()).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Synchronized private fun salt(ctx: Context): String {
        val p = ctx.getSharedPreferences("net_identity", Context.MODE_PRIVATE)
        return p.getString("salt", null) ?: java.util.UUID.randomUUID().toString().also { check(p.edit().putString("salt", it).commit()) }
    }

    /** "Wi-Fi · 192.168.1.1" → "Wi-Fi": what was learned before 1.0.69 (all Wi-Fi as one) is still used as a start. */
    fun family(key: String): String = key.substringBefore(" · ")
}
