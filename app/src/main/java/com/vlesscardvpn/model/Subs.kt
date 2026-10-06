package com.vlesscardvpn.model

/** Public subscription sources (checked 2026-10: alive, plain or base64 share links). */
data class SubSource(val url: String, val title: String, val group: String, val whitelist: Boolean = false, val default: Boolean = false)

object Subs {
    private fun gh(path: String) = "https://raw.githubusercontent.com/$path"
    private const val IG = "igareck/vpn-configs-for-russia/main"
    const val RU = "Для России"
    const val WL = "Белые списки (мобильный интернет)"
    const val WORLD = "Мировые сборники (много, но слабее)"

    val CATALOG: List<SubSource> = listOf(
        SubSource(gh("$IG/BLACK_VLESS_RUS_mobile.txt"), "igareck · VLESS для мобильного", RU, default = true),
        SubSource(gh("$IG/BLACK_VLESS_RUS.txt"), "igareck · VLESS", RU, default = true),
        SubSource(gh("$IG/BLACK_SS+All_RUS.txt"), "igareck · Shadowsocks и все", RU, default = true),
        SubSource(gh("$IG/BLACK_SS_WEAK_DPI_RUS.txt"), "igareck · SS для слабого DPI", RU, default = true),
        SubSource(gh("RKPchannel/RKP_bypass_configs/main/blacklist.txt"), "РосКомПозор · чёрные списки", RU, default = true),
        SubSource(gh("Maskkost93/kizyak-vpn-4.0/main/kizyakbeta7.txt"), "Кизяк VPN", RU, default = true),
        SubSource(gh("kort0881/vpn-vless-configs-russia/main/output/vless.txt"), "kort0881 · большой сборник VLESS", RU),
        SubSource(gh("$IG/Vless-Reality-White-Lists-Rus-Mobile.txt"), "igareck · белые списки", WL, whitelist = true, default = true),
        SubSource(gh("RKPchannel/RKP_bypass_configs/main/whitelist.txt"), "РосКомПозор · белые списки", WL, whitelist = true, default = true),
        SubSource(gh("zieng2/wl/main/vless_lite.txt"), "zieng2 · белые списки lite", WL, whitelist = true, default = true),
        SubSource(gh("zieng2/wl/main/vless_universal.txt"), "zieng2 · белые списки universal", WL, whitelist = true),
        SubSource(gh("Maskkost93/kizyak-vpn-4.0/main/kizyaktestru.txt"), "Кизяк · RU-тест", WL, whitelist = true),
        SubSource(gh("Epodonios/v2ray-configs/main/All_Configs_Sub.txt"), "Epodonios", WORLD),
        SubSource(gh("mahdibland/V2RayAggregator/master/sub/sub_merge.txt"), "V2RayAggregator", WORLD),
        SubSource(gh("MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/all_sub.txt"), "MatinGhanbari", WORLD),
        SubSource(gh("sakha1370/OpenRay/main/output/all_valid_proxies.txt"), "OpenRay (проверенные)", WORLD),
        SubSource(gh("ebrasha/free-v2ray-public-list/main/V2Ray-Config-By-EbraSha.txt"), "EbraSha", WORLD),
        SubSource(gh("roosterkid/openproxylist/main/V2RAY_RAW.txt"), "openproxylist", WORLD),
    )
    private val byUrl = CATALOG.associateBy { it.url }
    fun find(url: String): SubSource? = byUrl[url]
    fun title(url: String): String = find(url)?.title ?: url.substringAfter("://").take(50)

    /** White-list sources: by catalog flag, or by name for user links. */
    fun isWhitelist(url: String): Boolean = find(url)?.whitelist ?: (url.contains("white", true) || url.contains("/wl/", true))

    private val RAW = Regex("https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)")
    fun mirrors(url: String): List<String> {
        val m = RAW.find(url) ?: return listOf(url)
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
