package com.vlesscardvpn.domain

data class VlessConfig(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val protocolType: String = "vless", // vless, vmess, trojan, ss
    val flow: String = "xtls-rprx-vision",
    val security: String = "reality",
    val sni: String = "yandex.ru",
    val fingerprint: String = "chrome",
    val publicKey: String = "",
    val shortId: String = "",
    val remark: String = "",
    val isActive: Boolean = false,
    val pingMs: Int = -1,
    val isFree: Boolean = false,
    val country: String = "Unknown",
    val addedAt: Long = System.currentTimeMillis()
)

data class AppSettings(
    val isDarkTheme: Boolean = true,
    val autoSelectBestPing: Boolean = true,
    val enableRuDirect: Boolean = true,
    val blockQuicYouTube: Boolean = true,
    val customSniOverride: String = "yandex.ru",
    val customDnsProvider: String = "Cloudflare (1.1.1.1)",
    val mtuSize: Int = 1400,
    val autoReconnectOnNetworkChange: Boolean = true,
    val autoTestAfterImport: Boolean = true,
    val includeFreeNodesInMainList: Boolean = true,
    val showOnlyWorkingNodes: Boolean = false,
    val deduplicateNodes: Boolean = true,
    val maxFreeNodesToAdd: Int = 100,
    val autoFetchSources: List<String> = listOf(
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/clean/vless.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/githubmirror/ru-sni/vless_ru.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/configs/vless_reality.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS.txt",
        "https://raw.githubusercontent.com/AvenCores/goida-vpn-configs/raw/refs/heads/main/githubmirror/26.txt",
        "https://raw.githubusercontent.com/ByeWhiteLists/ByeWhiteLists2/refs/heads/main/ByeWhiteLists2.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/vmess.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/trojan.txt",
        "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/Splitted-By-Protocol/ss.txt",
        "https://raw.githubusercontent.com/Epodonios/v2ray-configs/main/Splitted-By-Protocol/vless.txt",
        "https://raw.githubusercontent.com/MhdiTaheri/V2rayCollector/main/sub/vless",
        "https://raw.githubusercontent.com/Mosifree/-FREE2CONFIG/refs/heads/main/Reality",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/all_configs.txt",
        "https://raw.githubusercontent.com/sakazxc1400-creator/free-vpn-sub/main/sources.txt"
    )
)
