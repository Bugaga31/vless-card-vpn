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
    val masked: Boolean get() = args.any { ByeDpiArgs.SNI_PLACEHOLDER in it || it == DirectStrategies.MASK_POOL }
    /** ByeDPI `--auto` groups learn per site: the first connection may be spent detecting the DPI. */
    val adaptive: Boolean get() = args.any { it.startsWith("--auto") }
    fun argv(port: Int, mask: String = ByeDpiArgs.DEFAULT_MASK): List<String> {
        val domain = ByeDpiArgs.maskDomain(mask)
        val body = args.flatMap { a ->
            if (a == DirectStrategies.MASK_POOL) DirectStrategies.maskPool(domain).flatMap { listOf("--fake-sni", it) }
            else listOf(a.replace(ByeDpiArgs.SNI_PLACEHOLDER, domain))
        }
        return when (engine) {
            DpiEngine.BYEDPI -> ByeDpiArgs.loopbackPrefix(port) + body
            DpiEngine.TPWS -> DirectStrategies.tpwsPrefix(port) + body
        }
    }
}

object DirectStrategies {
    const val CUSTOM_ID = "BYEDPI#CUSTOM"
    const val DEADLINE_MS = 150_000L
    /** Expands to several `--fake-sni` (ByeDPI picks one at random per connection): user's mask first. */
    const val MASK_POOL = "{mask_pool}"
    private val POOL = listOf("ya.ru", "vk.com", "gosuslugi.ru", "ozon.ru", "mail.ru")
    fun maskPool(userMask: String): List<String> = (listOf(ByeDpiArgs.maskDomain(userMask)) + POOL).distinct()
    const val CHECK_TIMEOUT_MS = 5000

    private fun bye(p: ByeDpiPreset) = DirectStrategy("BYEDPI#${p.name}", p.label, DpiEngine.BYEDPI, p.extraArgs)
    private fun tpws(id: String, label: String, vararg a: String) = DirectStrategy("TPWS#$id", "zapret · $label", DpiEngine.TPWS, a.toList())

    /** Own idea: split at the SNI with a TLS-record split first; only after a DPI reset/timeout send a low-TTL masked fake. */
    val VCARD_STEALTH = DirectStrategy("BYEDPI#VCARD_STEALTH", "VLESS Card · сплит SNI → маскировка", DpiEngine.BYEDPI,
        listOf("--split", "1+s", "--disorder", "3+s", "--tlsrec", "1+s", "--auto=torst",
            "--fake", "-1", "--ttl", "6", "--fake-sni", ByeDpiArgs.SNI_PLACEHOLDER, "--fake-tls-mod", "rand,orig"))

    /**
     * Own idea: adaptive cascade. Every site starts with the gentlest desync (reverse order + TLS record at
     * the SNI). Only when the DPI answers with a reset/timeout/TLS error does ByeDPI retry that site with the
     * next level (OOB byte + SNI disorder), and only after a second failure with fakes whose SNI rotates
     * through a pool of whitelisted Russian domains. The working level is cached per IP.
     */
    val VCARD_CASCADE = DirectStrategy("BYEDPI#VCARD_CASCADE", "VLESS Card · адаптивный каскад", DpiEngine.BYEDPI,
        listOf("--disorder", "1", "--tlsrec", "1+s",
            "--auto=torst,ssl_err", "--oob", "1", "--disorder", "1+s", "--tlsrec", "1+s",
            "--auto=torst,ssl_err", "--split", "1+s", "--disorder", "3+s", "--fake", "-1", "--ttl", "5", MASK_POOL,
            "--fake-tls-mod", "rand,orig", "--timeout", "3"))
    /** Own idea: shred the SNI into 3-byte TCP pieces (4 cuts) inside its own TLS record, first byte sent last. */
    val VCARD_SHRED = DirectStrategy("BYEDPI#VCARD_SHRED", "VLESS Card · шредер SNI", DpiEngine.BYEDPI,
        listOf("--disorder", "1", "--split", "2:4:3+s", "--tlsrec", "1+s"))
    /** Own idea: record-layer version 0x0304 (RFC 8446: receivers must ignore it) breaks naive "16 03 01" DPI signatures. */
    val VCARD_RECVER = DirectStrategy("BYEDPI#VCARD_RECVER", "VLESS Card · другая версия TLS-записи", DpiEngine.BYEDPI,
        listOf("--tlsminor", "4", "--tlsrec", "1+s", "--disorder", "1"))
    /** Same SNI-shredding idea with zapret: TLS record cut inside the SNI + cuts at every host boundary, reversed. */
    val VCARD_TPWS = tpws("VCARD_SHRED", "VLESS Card · шредер хоста", "--tlsrec=sniext+1", "--split-pos=1,host,midsld,endhost-1", "--disorder")
    val OWN: List<DirectStrategy> get() = listOf(VCARD_CASCADE, VCARD_SHRED, VCARD_STEALTH, VCARD_TPWS, VCARD_RECVER)

    /** Order: what ByeByeDPI users report working most often first; TTL fakes (break near servers) last. */
    val BUILT_IN: List<DirectStrategy> = listOf(
        bye(ByeDpiPreset.OOB_THEN_DISORDER),
        VCARD_CASCADE,
        bye(ByeDpiPreset.DISORDER),
        tpws("SPLIT_DISORDER", "сплит + disorder", "--split-pos=1,midsld", "--disorder"),
        VCARD_SHRED,
        bye(ByeDpiPreset.MULTI_DISORDER),
        VCARD_STEALTH,
        VCARD_TPWS,
        tpws("TLSREC", "TLS-записи по SNI", "--tlsrec=sniext", "--split-pos=1,midsld"),
        VCARD_RECVER,
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
