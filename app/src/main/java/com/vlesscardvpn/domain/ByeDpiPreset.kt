package com.vlesscardvpn.domain

/** Real pinned ByeDPI options, not a new cipher or a promise of network-wide bypass. */
enum class ByeDpiPreset(val label: String, val extraArgs: List<String>) {
    COMBINED("ByeDPI · TCP + TLS", listOf("--split", "1+s", "--tlsrec", "1+s")),
    TCP_ONLY("ByeDPI · TCP", listOf("--split", "1+s")),
    TLS_RECORD_ONLY("ByeDPI · TLS-записи", listOf("--tlsrec", "1+s")),
    SNI_MIDDLE("ByeDPI · середина SNI", listOf("--split", "0+sm", "--tlsrec", "0+sm")),
    SNI_EDGES("ByeDPI · границы SNI", listOf("--split", "1+s", "--split", "-1+se", "--tlsrec", "1+s", "--tlsrec", "-1+se"));
    fun arguments(port: Int): List<String> {
        require(port in 1024..65535)
        return listOf("--ip", "127.0.0.1", "--port", port.toString(), "--max-conn", "128", "--timeout", "4") + extraArgs
    }
    companion object {
        fun remembered(value: String?): ByeDpiPreset? =
            if (value?.startsWith("BYEDPI#") == true) entries.firstOrNull { it.name == value.substringAfter('#') } else null
        fun order(seed: Int, remembered: String? = null): List<ByeDpiPreset> {
            val start = ((seed % entries.size) + entries.size) % entries.size
            val rotated = (0 until entries.size).map { entries[(start + it) % entries.size] }
            val preferred = remembered(remembered)
            return rotated.sortedBy { if (it == preferred) 0 else 1 }
        }
    }
}
