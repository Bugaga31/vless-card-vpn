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

    fun compatible(m: Mask, s: com.vlesscardvpn.model.Server): Boolean = when {
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
    )

    /**
     * 7 fingerprints × 12 fragment modes × (direct | via the current DPI strategy) = 168,
     * plus 7 fingerprints × 21 fixed strategies (5 own VLESS Card, 7 zapret, 9 ByeDPI) = 147. Total 315.
     */
    val ALL: List<Mask> = buildList {
        for (via in listOf(false, true)) for (f in FRAGMENTS) for (fp in FINGERPRINTS) {
            val id = "${fp}.${f[0]}" + if (via) ".b" else ""
            val title = FP_TITLES.getValue(fp) + ", " + f[1] + if (via) " + обход DPI" else ""
            add(Mask(id, title, fp, f[2], f[3], f[4], f[5], if (via) CURRENT_DPI else ""))
        }
        for (st in com.vlesscardvpn.core.DpiStrategies.BUILT_IN) for (fp in FINGERPRINTS)
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

    fun searchOrder(byeDpiAvailable: Boolean, server: com.vlesscardvpn.model.Server? = null): List<Mask> {
        val first = listOf("chrome.n", "chrome.h4", "chrome.p2", "firefox.h2", "safari.p3", "edge.h5", "chrome.h1", "ios.p1",
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
