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
    /** «Мои маскировки»: own UDP noise items (Xray finalmask noise JSON) instead of a [Masks.NOISES] preset. */
    val noiseJson: String = "",
    /** Made by the user (Settings → Мои маскировки) or imported from a vcmask:// link. */
    val custom: Boolean = false,
    /** Bred by [MaskLab] from masks that passed on the user's networks (Settings.autoMasks). */
    val auto: Boolean = false,
    // ---- VLESS Card signature layers (not packet splitting): what the TSPU sees besides fragments
    /** «Белый SNI»: a whitelisted Russian domain in the TLS hello (only servers pinned by certificate hash, where SNI is free). */
    val sni: String = "",
    /** ALPN like a browser ("h2,http/1.1") or plain "http/1.1" (TLS, not REALITY). */
    val alpn: String = "",
    /** «Узкий канал»: TCP MSS clamp — the kernel itself cuts every packet small, like an old 3G / satellite link (no Xray timing pattern). */
    val mss: Int = 0,
    /** «Один поток»: multiplexing — dozens of app connections become one TLS stream (no burst of handshakes to a foreign IP). */
    val mux: Boolean = false,
    /** «Быстрый старт»: TCP Fast Open — the first data rides in the SYN, the DPI that waits for a handshake sees an odd start (falls back by itself). */
    val tfo: Boolean = false,
    /** «Путь через IPv6»: server name resolved to IPv6 first (many TSPU filter IPv6 weaker), IPv4 as a race fallback. */
    val ipv6: Boolean = false,
) {
    val signature: Boolean get() = sni.isNotEmpty() || alpn.isNotEmpty() || mss > 0 || mux || tfo || ipv6
    val viaByeDpi: Boolean get() = dpi.isNotEmpty()
    val hasNoise: Boolean get() = noise.isNotEmpty() || noiseJson.isNotEmpty()
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

    /** Russian domains that pass «white lists» of mobile operators: used as «белый SNI». */
    val WHITE_SNI = listOf("vk.com", "ya.ru", "gosuslugi.ru", "ozon.ru")
    /** Wider pool of allowed names for the mask evolution (all present in the open whitelist geosite-cheburnet, MIT). */
    val WHITE_POOL = WHITE_SNI + listOf("mail.ru", "yandex.ru", "wildberries.ru", "avito.ru", "sberbank.ru", "2gis.ru", "rutube.ru",
        "kinopoisk.ru", "dzen.ru", "max.ru", "tbank.ru", "ok.ru", "vkvideo.ru", "x5.ru")
    val ALPNS = listOf("h2,http/1.1", "http/1.1")
    val MSS_VALUES = listOf(536, 300, 120, 88)

    /** Signature layers need a suitable server; then the usual rules apply. */
    fun signatureOk(m: Mask, s: com.vlesscardvpn.model.Server): Boolean {
        val tls = s.security == "tls" && s.protocol != "hysteria2" && s.isTcpBased
        if (m.sni.isNotEmpty() && !(tls && s.pcs.isNotEmpty())) return false
        if (m.alpn.isNotEmpty()) {
            if (!tls) return false
            when (s.network) {
                "ws", "httpupgrade" -> if (m.alpn != "http/1.1") return false
                "grpc", "h2", "xhttp" -> if (!m.alpn.startsWith("h2")) return false
            }
        }
        if (m.mss > 0 && (!s.isTcpBased || m.viaByeDpi)) return false
        if (m.mux && !XrayConfigBuilder.muxable(s)) return false
        if (m.tfo && (!s.isTcpBased || m.viaByeDpi)) return false
        if (m.ipv6 && (m.viaByeDpi || s.address.all { it.isDigit() || it == '.' } || ':' in s.address)) return false
        return true
    }

    fun compatible(m: Mask, s: com.vlesscardvpn.model.Server): Boolean = signatureOk(m, s) && compatible0(m, s)

    private fun compatible0(m: Mask, s: com.vlesscardvpn.model.Server): Boolean = when {
        m.hop == HOP_WARP -> isWarp(s)
        m.hop.isNotEmpty() -> s.protocol == "wireguard"
        m.noise.isNotEmpty() && WG_NOISES.any { it[0] == m.noise } -> s.protocol == "wireguard"
        m.hasNoise -> !s.isTcpBased && s.protocol != "xray"
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
        // 1.0.66: more own shapes — the TSPU learns fixed patterns, so every family has a different rhythm.
        listOf("l4", "лесенка вниз 60→20→5→1", "tlshello", "", "", "0", "40-60,15-20,4-6,1-1,1-1", "1-2,2-4,4-8,8-12,1-2"),
        listOf("l5", "рваный ритм: мелко-крупно", "tlshello", "", "", "0", "1-2,30-60,1-2,30-60,1-2,100-200", "5-10,1-2,5-10,1-2,5-10,1-2"),
        listOf("l6", "SNI по 1 байту с паузами", "tlshello", "", "", "0", "70-110,1-1,1-1,1-1,1-1,1-1,1-1,1-1,1-1,300-600", "1-2,3-5,3-5,3-5,3-5,3-5,3-5,3-5,3-5,1-2"),
        listOf("p6", "дробление 1-4 пакетов 1-10, паузы 2-6", "1-4", "1-10", "2-6", "6"),
        listOf("h7", "дробление Hello 1-2 байта, пауза 10-30", "tlshello", "1-2", "10-30", "4"),
        listOf("p7", "дробление 2 пакетов 30-80", "1-2", "30-80", "3-8", "0"),
        listOf("l7", "лесенка Фибоначчи 1,1,2,3,5,8,13,21", "tlshello", "", "", "0", "1-1,1-1,2-2,3-3,5-5,8-8,13-13,21-21", "1-1,1-1,2-2,3-3,5-5,8-8,13-13,21-21"),
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
        listOf("z10", "AmneziaWG: 1 большой + 6 мелких", """[{"rand":"900-1200","delay":"1-3"},{"rand":"10-40","delay":"1-2"},{"rand":"10-40","delay":"1-2"},{"rand":"10-40","delay":"1-2"},{"rand":"10-40","delay":"1-2"},{"rand":"10-40","delay":"1-2"},{"rand":"10-40","delay":"1-2"}]"""),
        listOf("z11", "шум под QUIC + мусор AmneziaWG", """[{"type":"exp","packet":"<b c3 00000001 08><r 8><b 00 00 44 d0><r 1150>","delay":"2-5"},{"rand":"40-70","delay":"1-3"},{"rand":"40-70","delay":"1-3"},{"rand":"40-70","delay":"1-3"}]"""),
        listOf("z12", "шум под онлайн-игру (8 пакетов)", """[{"rand":"20-60","delay":"8-16"},{"rand":"20-60","delay":"8-16"},{"rand":"20-60","delay":"8-16"},{"rand":"20-60","delay":"8-16"},{"rand":"20-60","delay":"8-16"},{"rand":"20-60","delay":"8-16"},{"rand":"20-60","delay":"8-16"},{"rand":"20-60","delay":"8-16"}]"""),
        listOf("z9", "AmneziaWG: 10 пакетов 50-1000 байт", """[{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"},{"rand":"50-1000","delay":"1-2"}]"""),
    )
    const val HOP_LOCAL = "hl"
    const val HOP_WARP = "hw"
    /** Ports Cloudflare WARP answers on (the same on every endpoint IP). */
    const val WARP_PORTS = "500,854,859,864,878,880,890,891,894,903,908,928,934,939,942,943,945,946,955,968,987,988,1002,1010,1014,1018,1070,1074,1180,1387,1701,1843,2371,2408,2506,3138,3476,3581,3854,4177,4198,4233,4500,5279,5956,7103,7152,7156,7281,7559,8319,8742,8854,8886"
    private val NOISE_BY_ID = (NOISES + WG_NOISES).associate { it[0] to it[2] }
    fun noiseItems(id: String): String? = NOISE_BY_ID[id]

    /**
     * 7 fingerprints × 22 fragment modes × (direct | via the current DPI strategy) = 308, 4 UDP + 8 WireGuard noise masks, 18 port-hopping,
     * plus 7 fingerprints × 21 fixed strategies (5 own VLESS Card, 7 zapret, 9 ByeDPI) = 147. Total 485 + 33 signature (SNI, MSS, ALPN, one stream, TFO, IPv6) = 518 (+ «Мои маскировки» and auto masks of MaskLab).
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
            for (n in listOf("z1", "z5", "z6", "z7", "z9", "z10", "z11", "z12")) add(Mask("chrome.$n.${h.first}", "WireGuard: " + ((NOISES + WG_NOISES).first { it[0] == n }[1]) + " + " + h.second, "chrome", noise = n, hop = h.first))
        }
        // VLESS Card signature masks: not splitting by Xray, but other things the TSPU looks at.
        for (d in WHITE_SNI) add(Mask("vc.sni.$d", "Фирменная: белый SNI $d", "chrome", sni = d))
        for (d in WHITE_SNI.take(2)) add(Mask("vc.sni.$d.l2", "Фирменная: белый SNI $d + шредер SNI", "firefox", "tlshello", "", "", "0",
            lengths = "90-120,1-2,1-2,1-2,1-2,1-3,1-3,200-400", delays = "1-3,1-2,1-2,1-2,1-2,1-2,1-2,1-3", sni = d))
        add(Mask("vc.mss536", "Фирменная: узкий канал MSS 536 (как старый 3G)", "chrome", mss = 536))
        add(Mask("vc.mss300", "Фирменная: узкий канал MSS 300", "firefox", mss = 300))
        add(Mask("vc.mss120", "Фирменная: микро-пакеты MSS 120", "safari", mss = 120))
        add(Mask("vc.mss88", "Фирменная: минимальные пакеты MSS 88", "chrome", mss = 88))
        add(Mask("vc.mss120.l6", "Фирменная: MSS 120 + SNI по байту", "firefox", "tlshello", "", "", "0",
            lengths = "70-110,1-1,1-1,1-1,1-1,1-1,1-1,1-1,1-1,300-600", delays = "1-2,3-5,3-5,3-5,3-5,3-5,3-5,3-5,3-5,1-2", mss = 120))
        add(Mask("vc.alpn.br", "Фирменная: ALPN как у браузера", "chrome", alpn = "h2,http/1.1"))
        add(Mask("vc.alpn.11", "Фирменная: ALPN только HTTP/1.1", "safari", alpn = "http/1.1"))
        add(Mask("vc.mux", "Фирменная: один поток (мультиплекс)", "chrome", mux = true))
        add(Mask("vc.mux.mss300", "Фирменная: один поток + узкий канал", "firefox", mss = 300, mux = true))
        add(Mask("vc.ghost", "Фирменная «Невидимка»: Safari, ALPN браузера, MSS 300, один поток", "safari", alpn = "h2,http/1.1", mss = 300, mux = true))
        add(Mask("vc.ghost.sni", "Фирменная «Невидимка+»: белый SNI vk.com, MSS 120, один поток", "chrome", sni = "vk.com", mss = 120, mux = true))
        add(Mask("vc.ghost.l1", "Фирменная «Невидимка»: лесенка + MSS 300 + ALPN браузера", "chrome", "tlshello", "", "", "0",
            lengths = "1-1,2-4,5-10,20-60", delays = "1-2,2-4,3-6,5-10", alpn = "h2,http/1.1", mss = 300))
        // 1.0.71: more signature masks — fast open, IPv6 path, combinations
        add(Mask("vc.tfo", "Фирменная: быстрый старт (TCP Fast Open)", "chrome", tfo = true))
        add(Mask("vc.tfo.mss300", "Фирменная: быстрый старт + узкий канал", "firefox", mss = 300, tfo = true))
        add(Mask("vc.v6", "Фирменная: путь через IPv6", "chrome", ipv6 = true))
        add(Mask("vc.v6.l1", "Фирменная: IPv6 + лесенка", "chrome", "tlshello", "", "", "0", lengths = "1-1,2-4,5-10,20-60", delays = "1-2,2-4,3-6,5-10", ipv6 = true))
        add(Mask("vc.v6.mss300", "Фирменная: IPv6 + узкий канал", "safari", mss = 300, ipv6 = true))
        for (d in WHITE_SNI) add(Mask("vc.sni.$d.mss120", "Фирменная: белый SNI $d + MSS 120", "chrome", sni = d, mss = 120))
        add(Mask("vc.alpn.br.h1", "Фирменная: ALPN браузера + дробление Hello 1-3", "chrome", "tlshello", "1-3", "1-3", "0", alpn = "h2,http/1.1"))
        add(Mask("vc.alpn.br.p1", "Фирменная: iPhone, ALPN браузера + 1-й пакет 1-5", "ios", "1-1", "1-5", "1-3", "0", alpn = "h2,http/1.1"))
        add(Mask("vc.mux.l5", "Фирменная: один поток + рваный ритм", "safari", "tlshello", "", "", "0",
            lengths = "1-2,30-60,1-2,30-60,1-2,100-200", delays = "5-10,1-2,5-10,1-2,5-10,1-2", mux = true))
        add(Mask("vc.mux.b", "Фирменная: один поток через обход DPI", "chrome", dpi = CURRENT_DPI, mux = true))
        add(Mask("vc.ghost.v6", "Фирменная «Невидимка-6»: IPv6, Safari, ALPN браузера, MSS 300, один поток", "safari", alpn = "h2,http/1.1", mss = 300, mux = true, ipv6 = true))
        add(Mask("vc.ghost.tfo", "Фирменная «Невидимка-турбо»: быстрый старт, MSS 120, один поток", "chrome", mss = 120, mux = true, tfo = true))
        // Xray-engine strategies duplicate the outbound's own fragment masks, so they are not offered in front of servers.
        for (st in com.vlesscardvpn.core.DpiStrategies.BUILT_IN.filter { it.engine != com.vlesscardvpn.core.DpiEngine.XRAY }) for (fp in FINGERPRINTS)
            add(Mask("$fp.d:${st.id}", FP_TITLES.getValue(fp) + " + " + st.label, fp, dpi = st.id))
    }
    private val byId = ALL.associateBy { it.id }
    val DEFAULT: Mask = byId.getValue("chrome.n")

    /** «Мои маскировки» (Settings.myMasks), kept in sync by Store. Tried first by the mask search. */
    @Volatile var custom: List<Mask> = emptyList()
        set(v) { field = v; customById = (auto + v).associateBy { it.id } }
    /** Auto masks of the mask evolution (Settings.autoMasks), kept in sync by Store. */
    @Volatile var auto: List<Mask> = emptyList()
        set(v) { field = v; customById = (v + custom).associateBy { it.id } }
    @Volatile private var customById: Map<String, Mask> = emptyMap()

    fun byId(id: String?): Mask? = if (id.isNullOrEmpty()) null else byId[id] ?: customById[id]

    /** Noise items of a mask: its own JSON («Мои маскировки») or the preset. */
    fun noiseFor(m: Mask): String? = m.noiseJson.ifEmpty { null } ?: m.noise.takeIf { it.isNotEmpty() }?.let { noiseItems(it) }

    /**
     * Order in which "Подобрать маскировку" tries masks: most likely to pass Russian TSPU first, then the rest.
     * Covers every fragment mode and every fingerprint early instead of exhausting one dimension.
     */
    /** Fixed DPI strategy ids used by these masks (each needs its own local engine). */
    fun strategies(masks: Collection<Mask?>): Set<String> = masks.mapNotNull { it?.dpi }.filter { it.isNotEmpty() }.toSet()

    /** Families the user can switch on/off for the mask search (Settings.maskFamilies). */
    val FAMILIES = listOf("my" to "Мои маскировки", "brand" to "Фирменные VLESS Card (SNI, MSS, ALPN, один поток, IPv6, быстрый старт)", "plain" to "Без дробления", "frag" to "Дробление", "ladder" to "Лесенки (свои)", "viadpi" to "Через обход DPI",
        "own" to "Свои VLESS Card", "zapret" to "zapret", "byedpi" to "ByeDPI", "noise" to "UDP-шум", "auto" to "Авто-маски (эволюция)")

    fun family(m: Mask): String = when {
        m.custom -> "my"
        m.auto -> "auto"
        m.signature -> "brand"
        m.hasNoise || m.hop.isNotEmpty() -> "noise"
        m.dpi.startsWith("BYEDPI#VCARD") || m.dpi.startsWith("TPWS#VCARD") -> "own"
        m.dpi.startsWith("TPWS#") -> "zapret"
        m.dpi.isNotEmpty() && m.dpi != CURRENT_DPI -> "byedpi"
        m.dpi == CURRENT_DPI -> "viadpi"
        m.lengths.isNotEmpty() -> "ladder"
        m.packets.isNotEmpty() -> "frag"
        else -> "plain"
    }

    /**
     * How much a mask slows the connection itself: 0 none … 4. A tiny MSS cuts EVERY packet of the connection to ~100
     * bytes (not only the handshake) — pages still open fast, but video crawls. Such masks are tried later and lose
     * to a lighter one that also passes.
     */
    fun cost(m: Mask): Int = (when { m.mss in 1..200 -> 3; m.mss in 201..400 -> 2; m.mss > 0 -> 1; else -> 0 }) + (if (m.mux) 1 else 0) + (if (m.viaByeDpi) 1 else 0)

    /** Finer than [family]: what kind of trick it is (the mask search tries one of every kind first). */
    fun kind(m: Mask): String = family(m).let { f ->
        if (f != "brand") f else when { m.sni.isNotEmpty() -> "sni"; m.mss > 0 -> "mss"; m.mux -> "mux"; m.alpn.isNotEmpty() -> "alpn"; m.tfo -> "tfo"; else -> "v6" }
    }

    /** Round-robin over kinds keeping the order inside each: the first dozen tries cover every trick, not 12 variants of one. */
    fun diverse(list: List<Mask>): List<Mask> {
        val groups = LinkedHashMap<String, ArrayDeque<Mask>>()
        list.forEach { groups.getOrPut(kind(it)) { ArrayDeque() }.addLast(it) }
        val out = ArrayList<Mask>(list.size)
        while (groups.values.any { it.isNotEmpty() }) groups.values.forEach { q -> q.removeFirstOrNull()?.let(out::add) }
        return out
    }

    /** [searchOrder] limited to the families / fingerprints chosen in Settings (empty = all). Plain chrome stays as a baseline. */
    fun searchOrder(byeDpiAvailable: Boolean, server: com.vlesscardvpn.model.Server?, families: Collection<String>, fps: Collection<String>): List<Mask> =
        searchOrder(byeDpiAvailable, server).filter { m ->
            m.id == DEFAULT.id || m.custom || (families.isEmpty() || family(m) in families) && (fps.isEmpty() || m.fingerprint in fps || m.hasNoise)
        }

    fun searchOrder(byeDpiAvailable: Boolean, server: com.vlesscardvpn.model.Server? = null): List<Mask> {
        val first = listOf("chrome.n", "vc.sni.vk.com", "vc.mss300", "vc.ghost", "vc.sni.ya.ru.l2", "vc.mss120", "vc.alpn.br", "vc.mux", "vc.ghost.sni", "vc.mss120.l6", "vc.ghost.v6", "vc.sni.vk.com.mss120", "vc.tfo", "vc.v6", "vc.ghost.tfo", "vc.alpn.br.h1", "vc.mux.l5", "chrome.z5", "chrome.z10", "chrome.z1", "chrome.z5.hw", "chrome.z6.hw", "chrome.z9", "chrome.z1.hl", "chrome.z11.hw", "chrome.z12", "chrome.z7", "chrome.hw", "chrome.z2", "chrome.z3", "chrome.z4", "chrome.h4", "chrome.l1", "firefox.l2", "chrome.l4", "safari.l5", "firefox.l6", "chrome.p6", "firefox.l7", "chrome.h7", "safari.p7", "chrome.p2", "firefox.h2", "safari.p3", "edge.h5", "chrome.h1", "ios.p1", "safari.l3", "chrome.l2.b",
            "android.h3", "firefox.h4", "chrome.n.b", "chrome.h4.b", "firefox.p4", "chrome.p5", "chrome.h6", "qq.h2",
            "safari.n", "firefox.n", "edge.p2", "ios.h4", "android.p3", "safari.h1", "chrome.p1.b", "firefox.h3.b",
            // own VLESS Card masking and zapret in front of the server connection
            "chrome.d:BYEDPI#VCARD_CASCADE", "firefox.d:TPWS#VCARD_SHRED", "chrome.d:BYEDPI#VCARD_SHRED", "safari.d:BYEDPI#VCARD_STEALTH",
            "chrome.d:BYEDPI#VCARD_RECVER", "chrome.d:TPWS#SPLIT_DISORDER", "firefox.d:TPWS#TLSREC", "chrome.d:BYEDPI#OOB_THEN_DISORDER",
            "safari.d:TPWS#HOST_DISORDER", "chrome.d:BYEDPI#MULTI_DISORDER", "chrome.d:TPWS#TLSREC_OOB", "firefox.d:BYEDPI#MASK_AUTO_FAKE")
        // own masks right after the plain baseline: the user made them for this network
        // light masks first (stable: the curated order stays inside each weight); own masks keep their place up front
        val ordered = (listOfNotNull(byId["chrome.n"]) + custom + (first.mapNotNull { byId[it] } + ALL).distinct().filter { !it.custom }.sortedBy { cost(it) }).distinct()
        return ordered.filter { (byeDpiAvailable || !it.viaByeDpi) && (server == null || compatible(it, server)) }
    }
}
