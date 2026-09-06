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
    val customSniOverride: String = "yandex.ru",
    val autoTestAfterImport: Boolean = true,
    val includeFreeNodesInMainList: Boolean = true,
    val showOnlyWorkingNodes: Boolean = false,
    val maxFreeNodesToAdd: Int = 30,
    val autoFetchSources: List<String> = listOf(
        "https://raw.githubusercontent.com/GoldCaviar/vpn-configs-for-russia/refs/heads/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
        "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/configs/vless_reality.txt",
        "https://raw.githubusercontent.com/ebrasha/free-v2ray-public-list/main/all_configs.txt"
    )
)
