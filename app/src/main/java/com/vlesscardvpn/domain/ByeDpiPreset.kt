package com.vlesscardvpn.domain

/** Real pinned ByeDPI options, not a new cipher or a promise of network-wide bypass. */
enum class ByeDpiPreset(val label: String, val extraArgs: List<String>) {
    COMBINED("ByeDPI · TCP + TLS", listOf("--split", "1+s", "--tlsrec", "1+s")),
    TCP_ONLY("ByeDPI · TCP", listOf("--split", "1+s")),
    TLS_RECORD_ONLY("ByeDPI · TLS-записи", listOf("--tlsrec", "1+s")),
    SNI_MIDDLE("ByeDPI · середина SNI", listOf("--split", "0+sm", "--tlsrec", "0+sm")),
    SNI_EDGES("ByeDPI · границы SNI", listOf("--split", "1+s", "--split", "-1+se", "--tlsrec", "1+s", "--tlsrec", "-1+se")),
    // Upstream ByeDPI README recommends --disorder 1 on Linux (Android is Linux); ByeByeDPI ships it in its strategy tests.
    DISORDER("ByeDPI · обратный порядок", listOf("--disorder", "1")),
    // Upstream recommendation for stacks that retransmit from the loss point: split before disorder.
    SPLIT_DISORDER("ByeDPI · разбиение + обратный порядок", listOf("--split", "1+s", "--disorder", "3+s")),
    // Native in-process fallback group: start with disorder; only after a reset/timeout from DPI retry with a low-TTL fake.
    DISORDER_THEN_FAKE("ByeDPI · авто: порядок → фейк", listOf("--disorder", "1", "--auto=torst", "--fake", "-1", "--ttl", "8")),
    // ByeByeDPI strategy list: out-of-band urgent byte that DPI counts but the server's TCP stack drops (-o1).
    OOB("ByeDPI · OOB-байт", listOf("--oob", "1")),
    // ByeByeDPI "-o1 -At,r,s -d1": OOB first; only after a DPI reset/timeout fall back to disorder.
    OOB_THEN_DISORDER("ByeDPI · авто: OOB → порядок", listOf("--oob", "1", "--auto=torst", "--disorder", "1")),
    // Upstream disoob (-q1): disordered first part plus OOB byte.
    DISOOB("ByeDPI · OOB + обратный порядок", listOf("--disoob", "1")),
    // ByeByeDPI multi-position disorder/split around the SNI ("-d1 -s1+s -d3+s -s6+s -d9+s -s12+s").
    MULTI_DISORDER("ByeDPI · многократный порядок", listOf("--disorder", "1", "--split", "1+s", "--disorder", "3+s", "--split", "6+s", "--disorder", "9+s", "--split", "12+s"));
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
