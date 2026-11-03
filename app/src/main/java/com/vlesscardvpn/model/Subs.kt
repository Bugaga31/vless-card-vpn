package com.vlesscardvpn.model

/** Public subscription sources (checked 2026-10: alive, plain or base64 share links). */
data class SubSource(val url: String, val title: String, val group: String, val whitelist: Boolean = false, val default: Boolean = false)

object Subs {
    private fun gh(path: String) = "https://raw.githubusercontent.com/$path"
    private const val IG = "igareck/vpn-configs-for-russia/main"
    const val DIMAN = "https://gist.githubusercontent.com/Diman331/8c868070c0b5a5ec0c7d94507717b67b/raw/subscription.txt"
    const val OVI_FULL = "https://raw.githubusercontent.com/OVI-vpn/bs/main/full.txt"
    const val CLOUD_WORKS = "https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/works.txt"
    const val RU = "Для России"
    const val WL = "Белые списки (мобильный интернет)"
    const val WORLD = "Мировые сборники (много, но слабее)"

    val CATALOG: List<SubSource> = listOf(
        // Public cloud checks all collected reachable Xray-supported configurations; local ISP checks still decide.
        SubSource(CLOUD_WORKS, "VLESS Card · проверенные HTTPS, весь мир", WORLD, default = true),
        SubSource(gh("Bugaga31/vless-card-vpn/servers/alive_ru.txt"), "VLESS Card · облачная проверка (для России)", RU, default = true),
        SubSource(DIMAN, "Diman331 · подписка", WORLD, default = true),
        SubSource(gh("$IG/BLACK_VLESS_RUS_mobile.txt"), "igareck · VLESS для мобильного", RU, default = true),
        SubSource(gh("$IG/BLACK_VLESS_RUS.txt"), "igareck · VLESS", RU, default = true),
        SubSource(gh("$IG/BLACK_SS+All_RUS.txt"), "igareck · Shadowsocks и все", RU, default = true),
        SubSource(gh("$IG/BLACK_SS_WEAK_DPI_RUS.txt"), "igareck · SS для слабого DPI", RU, default = true),
        SubSource(gh("RKPchannel/RKP_bypass_configs/main/blacklist.txt"), "РосКомПозор · чёрные списки", RU, default = true),
        SubSource(gh("Maskkost93/kizyak-vpn-4.0/main/kizyakbeta7.txt"), "Кизяк VPN", RU, default = true),
        SubSource(gh("kort0881/vpn-vless-configs-russia/main/output/vless.txt"), "kort0881 · большой сборник VLESS", RU),
        SubSource(gh("FLAT447/v2ray-lists/main/BLACK_LTE.txt"), "FLAT447 · для мобильного (LTE)", RU),
        SubSource(gh("whoahaow/rjsxrd/main/githubmirror/bypass/bypass-all.txt"), "rjsxrd · обход блокировок", RU),
        SubSource(gh("$IG/Vless-Reality-White-Lists-Rus-Mobile.txt"), "igareck · белые списки", WL, whitelist = true, default = true),
        SubSource(gh("RKPchannel/RKP_bypass_configs/main/whitelist.txt"), "РосКомПозор · белые списки", WL, whitelist = true, default = true),
        SubSource(gh("zieng2/wl/main/vless_lite.txt"), "zieng2 · белые списки lite", WL, whitelist = true, default = true),
        SubSource(gh("zieng2/wl/main/vless_universal.txt"), "zieng2 · белые списки universal", WL, whitelist = true),
        SubSource(gh("Maskkost93/kizyak-vpn-4.0/main/kizyaktestru.txt"), "Кизяк · RU-тест", WL, whitelist = true),
        SubSource(gh("FLAT447/v2ray-lists/main/WHITE_LITE.txt"), "FLAT447 · белые списки lite", WL, whitelist = true),
        SubSource(gh("Bugaga31/vless-card-vpn/servers/alive.txt"), "VLESS Card · облачная проверка (весь мир)", WORLD),
        SubSource(gh("Epodonios/v2ray-configs/main/All_Configs_Sub.txt"), "Epodonios", WORLD),
        SubSource(gh("mahdibland/V2RayAggregator/master/sub/sub_merge.txt"), "V2RayAggregator", WORLD),
        SubSource(gh("MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/all_sub.txt"), "MatinGhanbari", WORLD),
        SubSource(gh("sakha1370/OpenRay/main/output/all_valid_proxies.txt"), "OpenRay (проверенные)", WORLD),
        SubSource(gh("ebrasha/free-v2ray-public-list/main/V2Ray-Config-By-EbraSha.txt"), "EbraSha", WORLD),
        SubSource(gh("roosterkid/openproxylist/main/V2RAY_RAW.txt"), "openproxylist", WORLD),
        SubSource(gh("barry-far/V2ray-Config/main/Sub1.txt"), "barry-far", WORLD),
        SubSource(gh("Mosifree/-FREE2CONFIG/main/Reality"), "FREE2CONFIG · Reality", WORLD),
        SubSource(gh("MhdiTaheri/V2rayCollector/main/sub/mix"), "V2rayCollector", WORLD),
        SubSource(gh("SoliSpirit/v2ray-configs/main/Protocols/vless.txt"), "SoliSpirit · VLESS", WORLD),
        // Additional public files: fetch/format checked, not availability evidence. Opt-in sources.
        SubSource("https://raw.githubusercontent.com/s0ulcoil/HavenVPN/refs/heads/main/Haven", "HavenVPN", WORLD),
        SubSource("https://raw.githubusercontent.com/opti4riponty-arch/VLESS-Co/refs/heads/main/VLESS%20%26%20Co", "VLESS & Co", WORLD),
        SubSource("https://raw.githubusercontent.com/flaafix/AetrisVPN/refs/heads/main/AetrisVPN.txt", "AetrisVPN", WORLD),
        SubSource("https://raw.githubusercontent.com/flaafix/AetrisVPN-black-list/refs/heads/main/configs.txt", "AetrisVPN · blacklist", WORLD),
        SubSource("https://raw.githubusercontent.com/flaafix/AetrisVPN-white-list-lite/refs/heads/main/AetrisVPN.txt", "AetrisVPN · whitelist lite", WL, whitelist = true),
        SubSource("https://gitverse.ru/api/repos/IvanBudko/Tarantul/raw/branch/master/tarantul.txt", "Tarantul", WORLD),
        SubSource("https://gitverse.ru/api/repos/Catlerok_glasha/catwhiteMIRROR/raw/branch/master/configs.txt", "catwhiteMIRROR", WL, whitelist = true),
        SubSource("https://gitverse.ru/api/repos/Pizduk/PizdukVPN/raw/branch/master/Mixer", "Mixer", WORLD),
        SubSource("https://hub.mos.ru/akelladejavu/bunker/-/raw/main/BEST_LIST.txt", "bunker · BEST_LIST", WORLD),
        SubSource("https://hub.mos.ru/akelladejavu/bunker/-/raw/main/WHITE_LIST.txt", "bunker · WHITE_LIST", WL, whitelist = true),
        // Requested 2026-10-28. A format check is not proof of reachability on the user's ISP.
        SubSource(OVI_FULL, "OVI VPN · Full", WORLD, default = true),
        SubSource("https://raw.githubusercontent.com/OVI-vpn/bs/main/premium.txt", "OVI VPN · Premium (пусто при проверке 28.10)", WORLD),
        SubSource("https://pozor.bond/sub/0bsk0rfna3rr", "pozor · 0bsk0rfna3rr (404 при проверке 28.10)", WORLD),
        SubSource("https://pozor.bond/sub/0qjvewd7qynv", "pozor · 0qjvewd7qynv (404 при проверке 28.10)", WORLD),
        SubSource("https://pozor.bond/sub/5rnwokp4nqcw", "pozor · 5rnwokp4nqcw (404 при проверке 28.10)", WORLD),
        SubSource("https://pozor.bond/sub/wfe5og2pse0p", "pozor · wfe5og2pse0p (404 при проверке 28.10)", WORLD),
        SubSource("https://pozor.bond/sub/f02gu3tqgql1", "pozor · f02gu3tqgql1 (404 при проверке 28.10)", WORLD),
        SubSource("https://pozor.bond/sub/2sdfe56lna", "pozor · 2sdfe56lna (404 при проверке 28.10)", WORLD),
    )
    private val byUrl = CATALOG.associateBy { it.url }
    fun find(url: String): SubSource? = byUrl[url]
    fun title(url: String): String = find(url)?.title ?: url.substringAfter("://").take(50)

    /** White-list sources: by catalog flag, or by name for user links. */
    fun isWhitelist(url: String): Boolean = find(url)?.whitelist ?: (url.contains("white", true) || url.contains("/wl/", true))

    private val RAW = Regex("https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)")
    fun mirrors(url: String): List<String> {
        // Never forward tokens/query strings or authenticated URLs to a public mirror.
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return listOf(url)
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return listOf(url)
        val m = RAW.matchEntire(url) ?: return listOf(url)
        val (owner, repo, branch, path) = m.destructured
        return buildList {
            add(url)
            if (owner == "igareck") {
                add("https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/$branch/$path")
                add("https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/$branch/$path")
            }
            add("https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path")
            add("https://raw.githack.com/$owner/$repo/$branch/$path")
        }
    }
}
