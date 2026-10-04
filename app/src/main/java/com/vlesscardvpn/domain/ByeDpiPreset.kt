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
    MULTI_DISORDER("ByeDPI · многократный порядок", listOf("--disorder", "1", "--split", "1+s", "--disorder", "3+s", "--split", "6+s", "--disorder", "9+s", "--split", "12+s")),
    // Masking (ByeByeDPI/zapret "fake SNI"): a fake ClientHello for a whitelisted domain ({sni}, default ya.ru)
    // goes first with TTL 8, so it expires before a distant server while the DPI sees the Yandex SNI.
    // No --md5sig: CI showed TCP_MD5SIG is unavailable on the Android emulator kernel (and many GKI phones).
    MASK_FAKE("ByeDPI · маскировка фейк-SNI", listOf("--disorder", "1", "--fake", "-1", "--ttl", "8", "--fake-sni", ByeDpiArgs.SNI_PLACEHOLDER)),
    // Whole fake first (ByeByeDPI "-f-1 -t8 -n ya.ru -Qr"): the fake chunk is cut from the template's start,
    // so after a split it may miss the SNI on short ClientHellos (seen in CI); keep it unsplit.
    MASK_FAKE_RAND("ByeDPI · маскировка + случайный TLS", listOf("--fake", "-1", "--ttl", "8", "--fake-sni", ByeDpiArgs.SNI_PLACEHOLDER, "--fake-tls-mod", "rand")),
    // Masking only after a DPI reset/timeout of plain disorder: never costs a working route.
    MASK_AUTO_FAKE("ByeDPI · авто: порядок → маскировка", listOf("--disorder", "1", "--auto=torst", "--fake", "-1", "--ttl", "8", "--fake-sni", ByeDpiArgs.SNI_PLACEHOLDER, "--fake-tls-mod", "rand"));
    val masked: Boolean get() = ByeDpiArgs.SNI_PLACEHOLDER in extraArgs
    fun arguments(port: Int, mask: String = ByeDpiArgs.DEFAULT_MASK): List<String> {
        val domain = ByeDpiArgs.maskDomain(mask)
        return ByeDpiArgs.loopbackPrefix(port) + extraArgs.map { it.replace(ByeDpiArgs.SNI_PLACEHOLDER, domain) }
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
