package com.vlesscardvpn.core

/**
 * 1.2.136: one Android network session keeps one learning identity. On the SAME network handle the cellular
 * operator name/code (TelephonyManager briefly returns blanks while the radio re-registers) or the Wi-Fi DNS
 * list (an IPv6 resolver expiring) could flicker and produce «another network»: a full handoff with every
 * server's runtime evidence reset and the route rebuilt. A key computed with less information than the one
 * already remembered for this handle is replaced by the remembered one; a more complete key still wins.
 * Process memory only, bounded, raw identifiers are never stored here (keys are already salted fingerprints).
 */
class NetworkIdentity(private val capacity: Int = 8) {
    private data class Entry(val key: String, val rank: Int)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized fun stable(handle: String, key: String, rank: Int): String {
        if (handle.isBlank()) return key
        val known = entries[handle]
        if (known != null && known.rank >= rank) return known.key
        entries[handle] = Entry(key, rank)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return key
    }

    @Synchronized fun clear() = entries.clear()

    companion object {
        val session = NetworkIdentity()
        /** Code (MCC+MNC) is what the cellular key is built from; a visible operator name adds a little. */
        fun cellularRank(name: String, code: String?): Int = (if (code != null) 2 else 0) + (if (name.isNotBlank()) 1 else 0)
        /** Router address identifies a Wi-Fi; a visible SSID identifies it better. */
        fun wifiRank(gateway: String, ssid: String): Int {
            val visible = ssid.trim().removeSurrounding("\"").takeUnless { it.isBlank() || it == "<unknown ssid>" || it == "0x" }
            return (if (visible != null) 2 else 0) + (if (gateway.isNotBlank()) 1 else 0)
        }
    }
}
