package com.vlesscardvpn.xray

/**
 * Per-server masking applied to the Xray outbound. Several selected servers can use different masks at the
 * same time (each outbound carries its own). Dimensions:
 *  - uTLS fingerprint of the ClientHello (JA3/JA4 looks like a real browser);
 *  - Xray finalmask "fragment": the first TLS record / first packets are split into small TCP segments with
 *    delays so the DPI cannot read SNI from one packet;
 *  - DPI front: the TCP connection to the server goes through a local desync engine — the current strategy
 *    (".b") or a fixed one ("fp.d:<id>"): own VLESS Card cascade / SNI shredder / fake SNI ya.ru, zapret, ByeDPI.
 */
data class Mask(
    val id: String,
    val title: String,
    val fingerprint: String,          // "" = keep the link's fp (or chrome)
    val packets: String = "",         // "" = no fragmentation; "tlshello" or "1-3" etc.
    val length: String = "",
    val delay: String = "",
    val maxSplit: String = "",
    /** "" = direct, [Masks.CURRENT_DPI] = the strategy chosen for this network, else a DpiStrategies id. */
    val dpi: String = "",
    /** Per-fragment lengths/delays ("1-1,2-4,5-10"): a "ladder" instead of equal pieces (Xray finalmask lengths/delays). */
    val lengths: String = "",
    val delays: String = "",
    /** UDP noise preset ([Masks.NOISES] id) for Hysteria2 / WireGuard / mKCP: junk datagrams before the handshake. */
    val noise: String = "",
    /**
     * WireGuard port hopping (Xray finalmask udphop): [Masks.HOP_LOCAL] = new local port every 10-20 s (a new flow for
     * the DPI, the server roams), [Masks.HOP_WARP] = also a new WARP port every 10-20 s (Cloudflare listens on 54 ports).
     */
    val hop: String = "",
) {
    val viaByeDpi: Boolean get() = dpi.isNotEmpty()
}

object Masks {
    const val CURRENT_DPI = "cur"
    val FINGERPRINTS = listOf("chrome", "firefox", "safari", "edge", "ios", "android", "qq")
    private val FP_TITLES = mapOf("chrome" to "Chrome", "firefox" to "Firefox", "safari" to "Safari", "edge" to "Edge",
        "ios" to "iPhone", "android" to "Android", "qq" to "QQ")
    /**
     * Checked against real Xray 26.9.30 servers (tools/xray-local-e2e.sh): REALITY handshakes only succeed with
     * these uTLS fingerprints; "android" breaks gRPC (h2); "randomized" broke every TLS server, so it is not offered.
     */
    val REALITY_FPS = setOf("chrome", "firefox", "safari")

    fun isWarp(s: com.vlesscardvpn.model.Server): Boolean = s.protocol == "wireguard" &&
        (s.source == "warp" || s.pbk == "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")

    fun compatible(m: Mask, s: com.vlesscardvpn.model.Server): Boolean = when {
        m.hop == HOP_WARP -> isWarp(s)
        m.hop.isNotEmpty() -> s.protocol == "wireguard"
        m.noise.isNotEmpty() && WG_NOISES.any { it[0] == m.noise } -> s.protocol == "wireguard"
        m.noise.isNotEmpty() -> !s.isTcpBased && s.protocol != "xray"
        s.protocol == "xray" -> m.id == DEFAULT.id // raw Xray JSON outbound: used as is
        m.viaByeDpi && !s.isTcpBased -> false
        m.viaByeDpi && m.dpi != CURRENT_DPI && m.packets.isNotEmpty() -> false
        m.packets.isNotEmpty() && !s.isTcpBased -> false
        s.security == "reality" -> m.fingerprint in REALITY_FPS
        s.network == "grpc" && s.security == "tls" -> m.fingerprint != "android"
        s.security != "tls" && s.protocol != "hysteria2" -> m.fingerprint == "chrome" // no TLS: fingerprint is irrelevant
        s.protocol == "hysteria2" -> m.fingerprint == "chrome" && m.packets.isEmpty() && !m.viaByeDpi
        else -> true
    }

    /** (id, title, packets, length, delay, maxSplit). */
    private val FRAGMENTS = listOf(
        listOf("n", "без дробления", "", "", "", ""),
        listOf("h1", "дробление Hello 1-3 байт", "tlshello", "1-3", "1-3", "0"),
        listOf("h2", "дробление Hello 3-8", "tlshello", "3-8", "2-5", "0"),
        listOf("h3", "дробление Hello 10-30", "tlshello", "10-30", "5-10", "0"),
        listOf("h4", "дробление Hello 50-100", "tlshello", "50-100", "10-20", "10"),
        listOf("h5", "дробление Hello 100-200", "tlshello", "100-200", "10-20", "10"),
        listOf("h6", "дробление Hello 200-400", "tlshello", "200-400", "5-15", "0"),
        listOf("p1", "дробление 1-го пакета 1-5", "1-1", "1-5", "1-3", "0"),
        listOf("p2", "дробление 1-3 пакетов 10-20", "1-3", "10-20", "10-20", "0"),
        listOf("p3", "дробление 1-3 пакетов 50-100", "1-3", "50-100", "5-10", "10"),
        listOf("p4", "дробление 1-5 пакетов 100-200", "1-5", "100-200", "10-30", "8"),
        listOf("p5", "дробление 1-2 пакетов, долгая пауза", "1-2", "20-60", "30-60", "4"),
        // VLESS Card ladders: different piece sizes in a row, so no fixed fragment size to fingerprint.
        listOf("l1", "лесенка 1→3→8→30 байт", "tlshello", "", "", "0", "1-1,2-4,5-10,20-60", "1-2,2-4,3-6,5-10"),
        listOf("l2", "шредер зоны SNI", "tlshello", "", "", "0", "90-120,1-2,1-2,1-2,1-2,1-3,1-3,200-400", "1-3,1-2,1-2,1-2,1-2,1-2,1-2,1-3"),
        listOf("l3", "1 байт + долгая пауза", "1-1", "", "", "0", "1-1,500-1000", "60-120,1-2"),
    )

    /** (id, title, noise items as JSON) — sent before the first UDP datagram; servers drop them. */
    val NOISES = listOf(
        listOf("z1", "шум: 3 случайных пакета", """[{"rand":"10-60","delay":"5-10"},{"rand":"10-60","delay":"5-10"},{"rand":"80-200","delay":"5-15"}]"""),
        listOf("z2", "шум под DNS-запрос", """[{"type":"exp","packet":"<r 2><b 0100 0001 0000 0000 0000 0279 6102 7275 0000 0100 01>","delay":"5-10"},{"rand":"20-80","delay":"5-10"}]"""),
        listOf("z3", "шум под STUN (звонок)", """[{"type":"exp","packet":"<b 0001 0000 2112 a442><r 12>","delay":"3-8"},{"type":"exp","packet":"<b 0001 0000 2112 a442><r 12>","delay":"3-8"}]"""),
        listOf("z4", "много мелкого шума", """[{"rand":"1-16","delay":"1-3"},{"rand":"1-16","delay":"1-3"},{"rand":"1-16","delay":"1-3"},{"rand":"1-16","delay":"1-3"},{"rand":"1-16","delay":"1-3"},{"rand":"1-16","delay":"1-3"}]"""),
    )
    /** WireGuard-only noise: AmneziaWG-style junk and look-alikes of protocols that the TSPU lets through. */
    val WG_NOISES = listOf(
        listOf("z5", "AmneziaWG: 4 мусорных пакета 40-70 байт", """[{"rand":"40-70","delay":"1-3"},{"rand":"40-70","delay":"1-3"},{"rand":"40-70","delay":"1-3"},{"rand":"40-70","delay":"1-3"}]"""),
        listOf("z6", "шум под QUIC (HTTP/3 браузера)", """[{"type":"exp","packet":"<b c3 00000001 08><r 8><b 00 00 44 d0><r 1150>","delay":"2-5"},{"type":"exp","packet":"<b c3 00000001 08><r 8><b 00 00 44 d0><r 1150>","delay":"2-5"}]"""),
        listOf("z7", "шум под DTLS (видеозвонок)", """[{"type":"exp","packet":"<b 16 fefd 0000 0000 0000 0000><r 2><b 01><r 3><b 0000><r 110>","delay":"3-6"},{"rand":"20-60","delay":"2-4"}]"""),
        listOf("z8", "шум под голос RTP", """[{"type":"exp","packet":"<b 80 60><r 10><r 160>","delay":"20-20"},{"type":"exp","packet":"<b 80 60><r 10><r 160>","delay":"20-20"},{"type":"exp","packet":"<b 80 60><r 10><r 160>","delay":"20-20"}]"""),
        listOf("z9", "AmneziaWG: 10 пакетов 50-1000 байт", """[{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"}]"""),
    )
    const val HOP_LOCAL = "hl"
    const val HOP_WARP = "hw"
    /** Ports Cloudflare WARP answers on (the same on every endpoint IP). */
    const val WARP_PORTS = "500,854,859,864,878,880,890,891,894,903,908,928,934,939,942,943,945,946,955,968,987,988,1002,1010,1014,1018,1070,1074,1180,1387,1701,1843,2371,2408,2506,3138,3476,3581,3854,4177,4198,4233,4500,5279,5956,7103,7152,7156,7281,7559,8319,8742,8854,8886"
    private val NOISE_BY_ID = (NOISES + WG_NOISES).associate { it[0] to it[2] }
    fun noiseItems(id: String): String? = NOISE_BY_ID[id]

    /**
     * 7 fingerprints × 15 fragment modes × (direct | via the current DPI strategy) = 210, 4 UDP noise masks,
     * plus 7 fingerprints × 21 fixed strategies (5 own VLESS Card, 7 zapret, 9 ByeDPI) = 147. Total 361.
     */
    val ALL: List<Mask> = buildList {
        for (via in listOf(false, true)) for (f in FRAGMENTS) for (fp in FINGERPRINTS) {
            val id = "${fp}.${f[0]}" + if (via) ".b" else ""
            val title = FP_TITLES.getValue(fp) + ", " + f[1] + if (via) " + обход DPI" else ""
            add(Mask(id, title, fp, f[2], f[3], f[4], f[5], if (via) CURRENT_DPI else "", f.getOrElse(6) { "" }, f.getOrElse(7) { "" }))
        }
        for (n in NOISES) add(Mask("chrome.${n[0]}", "UDP: " + n[1], "chrome", noise = n[0]))
        // WireGuard / WARP: protocol look-alike noise × port hopping (stays ahead of per-flow blocking).
        for (n in WG_NOISES) add(Mask("chrome.${n[0]}", "WireGuard: " + n[1], "chrome", noise = n[0]))
        for (h in listOf(HOP_LOCAL to "смена порта каждые 10-20 с", HOP_WARP to "прыжки по 54 портам WARP")) {
            add(Mask("chrome.${h.first}", "WireGuard: " + h.second, "chrome", hop = h.first))
            for (n in listOf("z1", "z5", "z6", "z7", "z9")) add(Mask("chrome.$n.${h.first}", "WireGuard: " + ((NOISES + WG_NOISES).first { it[0] == n }[1]) + " + " + h.second, "chrome", noise = n, hop = h.first))
        }
        // Xray-engine strategies duplicate the outbound's own fragment masks, so they are not offered in front of servers.
        for (st in com.vlesscardvpn.core.DpiStrategies.BUILT_IN.filter { it.engine != com.vlesscardvpn.core.DpiEngine.XRAY }) for (fp in FINGERPRINTS)
            add(Mask("$fp.d:${st.id}", FP_TITLES.getValue(fp) + " + " + st.label, fp, dpi = st.id))
    }
    private val byId = ALL.associateBy { it.id }
    val DEFAULT: Mask = byId.getValue("chrome.n")

    fun byId(id: String?): Mask? = if (id.isNullOrEmpty()) null else byId[id]

    /**
     * Order in which "Подобрать маскировку" tries masks: most likely to pass Russian TSPU first, then the rest.
     * Covers every fragment mode and every fingerprint early instead of exhausting one dimension.
     */
    /** Fixed DPI strategy ids used by these masks (each needs its own local engine). */
    fun strategies(masks: Collection<Mask?>): Set<String> = masks.mapNotNull { it?.dpi }.filter { it.isNotEmpty() }.toSet()

    /** Families the user can switch on/off for the mask search (Settings.maskFamilies). */
    val FAMILIES = listOf("plain" to "Без дробления", "frag" to "Дробление", "ladder" to "Лесенки (свои)", "viadpi" to "Через обход DPI",
        "own" to "Свои VLESS Card", "zapret" to "zapret", "byedpi" to "ByeDPI", "noise" to "UDP-шум")

    fun family(m: Mask): String = when {
        m.noise.isNotEmpty() || m.hop.isNotEmpty() -> "noise"
        m.dpi.startsWith("BYEDPI#VCARD") || m.dpi.startsWith("TPWS#VCARD") -> "own"
        m.dpi.startsWith("TPWS#") -> "zapret"
        m.dpi.isNotEmpty() && m.dpi != CURRENT_DPI -> "byedpi"
        m.dpi == CURRENT_DPI -> "viadpi"
        m.lengths.isNotEmpty() -> "ladder"
        m.packets.isNotEmpty() -> "frag"
        else -> "plain"
    }

    /** [searchOrder] limited to the families / fingerprints chosen in Settings (empty = all). Plain chrome stays as a baseline. */
    fun searchOrder(byeDpiAvailable: Boolean, server: com.vlesscardvpn.model.Server?, families: Collection<String>, fps: Collection<String>): List<Mask> =
        searchOrder(byeDpiAvailable, server).filter { m ->
            m.id == DEFAULT.id || (families.isEmpty() || family(m) in families) && (fps.isEmpty() || m.fingerprint in fps || m.noise.isNotEmpty())
        }

    fun searchOrder(byeDpiAvailable: Boolean, server: com.vlesscardvpn.model.Server? = null): List<Mask> {
        val first = listOf("chrome.n", "chrome.z5", "chrome.z5.hw", "chrome.z6.hw", "chrome.z9", "chrome.z1.hl", "chrome.z7", "chrome.hw", "chrome.z1", "chrome.z2", "chrome.z3", "chrome.z4", "chrome.h4", "chrome.l1", "firefox.l2", "chrome.p2", "firefox.h2", "safari.p3", "edge.h5", "chrome.h1", "ios.p1", "safari.l3", "chrome.l2.b",
            "android.h3", "firefox.h4", "chrome.n.b", "chrome.h4.b", "firefox.p4", "chrome.p5", "chrome.h6", "qq.h2",
            "safari.n", "firefox.n", "edge.p2", "ios.h4", "android.p3", "safari.h1", "chrome.p1.b", "firefox.h3.b",
            // own VLESS Card masking and zapret in front of the server connection
            "chrome.d:BYEDPI#VCARD_CASCADE", "firefox.d:TPWS#VCARD_SHRED", "chrome.d:BYEDPI#VCARD_SHRED", "safari.d:BYEDPI#VCARD_STEALTH",
            "chrome.d:BYEDPI#VCARD_RECVER", "chrome.d:TPWS#SPLIT_DISORDER", "firefox.d:TPWS#TLSREC", "chrome.d:BYEDPI#OOB_THEN_DISORDER",
            "safari.d:TPWS#HOST_DISORDER", "chrome.d:BYEDPI#MULTI_DISORDER", "chrome.d:TPWS#TLSREC_OOB", "firefox.d:BYEDPI#MASK_AUTO_FAKE")
        val ordered = (first.mapNotNull { byId[it] } + ALL).distinct()
        return ordered.filter { (byeDpiAvailable || !it.viaByeDpi) && (server == null || compatible(it, server)) }
    }
}
