package com.vlesscardvpn.domain

/** Local anti-DPI engine bundled as a non-root child process. */
enum class DpiEngine(val library: String, val title: String) {
    BYEDPI("libbyedpi.so", "ByeDPI"),
    TPWS("libtpws.so", "zapret tpws")
}

/**
 * "Без сервера" (как ByeByeDPI): the TUN sends traffic straight to a local ByeDPI/tpws SOCKS listener,
 * which desyncs the TLS/HTTP handshake towards the real site. No VPN server is involved, so this only
 * defeats DPI (throttling/SNI blocking), not IP blocks.
 */
data class DirectStrategy(val id: String, val label: String, val engine: DpiEngine, val args: List<String>) {
    val masked: Boolean get() = args.any { ByeDpiArgs.SNI_PLACEHOLDER in it }
    fun argv(port: Int, mask: String = ByeDpiArgs.DEFAULT_MASK): List<String> {
        val domain = ByeDpiArgs.maskDomain(mask)
        val body = args.map { it.replace(ByeDpiArgs.SNI_PLACEHOLDER, domain) }
        return when (engine) {
            DpiEngine.BYEDPI -> ByeDpiArgs.loopbackPrefix(port) + body
            DpiEngine.TPWS -> DirectStrategies.tpwsPrefix(port) + body
        }
    }
}

object DirectStrategies {
    const val CUSTOM_ID = "BYEDPI#CUSTOM"
    const val DEADLINE_MS = 120_000L
    const val CHECK_TIMEOUT_MS = 5000

    private fun bye(p: ByeDpiPreset) = DirectStrategy("BYEDPI#${p.name}", p.label, DpiEngine.BYEDPI, p.extraArgs)
    private fun tpws(id: String, label: String, vararg a: String) = DirectStrategy("TPWS#$id", "zapret · $label", DpiEngine.TPWS, a.toList())

    /** Own idea: split at the SNI with a TLS-record split first; only after a DPI reset/timeout send a low-TTL masked fake. */
    val VCARD_STEALTH = DirectStrategy("BYEDPI#VCARD_STEALTH", "VLESS Card · сплит SNI → маскировка", DpiEngine.BYEDPI,
        listOf("--split", "1+s", "--disorder", "3+s", "--tlsrec", "1+s", "--auto=torst",
            "--fake", "-1", "--ttl", "6", "--fake-sni", ByeDpiArgs.SNI_PLACEHOLDER, "--fake-tls-mod", "rand,orig"))

    /** Order: what ByeByeDPI users report working most often first; TTL fakes (break near servers) last. */
    val BUILT_IN: List<DirectStrategy> = listOf(
        bye(ByeDpiPreset.OOB_THEN_DISORDER),
        bye(ByeDpiPreset.DISORDER),
        tpws("SPLIT_DISORDER", "сплит + disorder", "--split-pos=1,midsld", "--disorder"),
        bye(ByeDpiPreset.MULTI_DISORDER),
        VCARD_STEALTH,
        tpws("TLSREC", "TLS-записи по SNI", "--tlsrec=sniext", "--split-pos=1,midsld"),
        bye(ByeDpiPreset.SPLIT_DISORDER),
        bye(ByeDpiPreset.MASK_AUTO_FAKE),
        tpws("OOB", "OOB-байт", "--oob", "--split-pos=1"),
        bye(ByeDpiPreset.DISOOB),
        tpws("HOST_DISORDER", "границы хоста + disorder", "--split-pos=1,host,midsld,endhost", "--disorder"),
        bye(ByeDpiPreset.COMBINED),
        tpws("TLSREC_OOB", "TLS-запись + OOB", "--tlsrec=sniext+1", "--split-pos=1,sniext+1", "--oob=tls"),
        bye(ByeDpiPreset.SNI_EDGES),
        bye(ByeDpiPreset.MASK_FAKE_RAND),
        tpws("MSS", "малый MSS", "--mss=88", "--split-pos=1")
    )

    fun byId(id: String?): DirectStrategy? = BUILT_IN.firstOrNull { it.id == id }

    /** User's own ByeDPI line first, then the strategy remembered as working, then the built-in list. */
    fun plan(custom: List<String>?, remembered: String?, tpwsAvailable: Boolean): List<DirectStrategy> = buildList {
        if (custom != null) add(DirectStrategy(CUSTOM_ID, AutoRouteAttempt.CUSTOM_LABEL, DpiEngine.BYEDPI, custom))
        byId(remembered)?.let { add(it) }
        addAll(BUILT_IN)
    }.filter { tpwsAvailable || it.engine != DpiEngine.TPWS }.distinctBy { it.id }

    fun tpwsPrefix(port: Int): List<String> {
        require(port in 1024..65535)
        return listOf("--socks", "--bind-addr=127.0.0.1", "--port=$port", "--maxconn=256")
    }
}
