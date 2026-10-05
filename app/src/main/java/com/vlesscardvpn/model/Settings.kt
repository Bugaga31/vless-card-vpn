package com.vlesscardvpn.model

import org.json.JSONArray
import org.json.JSONObject

enum class Mode(val title: String) { SERVERS("Серверы"), BYEDPI("Без сервера (ByeDPI)"), HYBRID("Гибрид") }

/** Balancer strategy across several selected servers (Xray routing balancer + observatory). */
enum class Balance(val xray: String, val title: String) {
    LEAST_PING("leastPing", "Самый быстрый"), ROUND_ROBIN("roundRobin", "По очереди"),
    RANDOM("random", "Случайно"), LEAST_LOAD("leastLoad", "Наименее загруженный")
}

data class Settings(
    val mode: Mode = Mode.SERVERS,
    val balance: Balance = Balance.LEAST_PING,
    val socksPort: Int = 10808,
    val ruDirect: Boolean = true,
    val blockAds: Boolean = false,
    val byeDpiArgs: String = DEFAULT_BYEDPI,
    val byeDpiSni: String = "ya.ru",
    /** Domains that go through ByeDPI in hybrid mode (geosite tags or domains). */
    val hybridDomains: List<String> = listOf("geosite:youtube", "geosite:discord", "geosite:twitter", "geosite:meta", "geosite:telegram"),
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val dnsUrl: String = "https://1.1.1.1/dns-query",
    val subscriptions: List<String> = DEFAULT_SUBSCRIPTIONS,
    /** DPI strategy id: [DPI_AUTO] (found for this network), "custom" (byeDpiArgs) or a built-in id. */
    val dpiStrategy: String = DPI_AUTO,
    /** Network name → strategy id that passed the search on that network. */
    val dpiRemembered: Map<String, String> = emptyMap(),
    // ---- privacy / hiding
    /** Random local SOCKS port + random login/password on every connect (apps can't find the proxy by scanning 10808). */
    val stealthSocks: Boolean = true,
    val blockStun: Boolean = false,
    val blockQuic: Boolean = false,
    val mux: Boolean = false,
    /** Each connect picks a random mask among the ones that passed the last search for that server. */
    val rotateMasks: Boolean = false,
    /** Russian domains resolved by Yandex DNS directly (with RU direct): RU sites see a normal Russian user. */
    val ruDns: Boolean = true,
    /** Package names: excluded from VPN, or (if [onlyApps]) the only ones that use it. */
    val apps: List<String> = emptyList(),
    val onlyApps: Boolean = false,
    val quietNotification: Boolean = false,
    val disguise: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode.name).put("balance", balance.name).put("socksPort", socksPort)
        .put("ruDirect", ruDirect).put("blockAds", blockAds).put("byeDpiArgs", byeDpiArgs).put("byeDpiSni", byeDpiSni)
        .put("hybridDomains", JSONArray(hybridDomains)).put("testUrl", testUrl).put("dnsUrl", dnsUrl)
        .put("subscriptions", JSONArray(subscriptions))
        .put("dpiStrategy", dpiStrategy).put("dpiRemembered", JSONObject(dpiRemembered as Map<*, *>))
        .put("stealthSocks", stealthSocks).put("blockStun", blockStun).put("blockQuic", blockQuic).put("mux", mux).put("rotateMasks", rotateMasks)
        .put("ruDns", ruDns).put("apps", JSONArray(apps)).put("onlyApps", onlyApps)
        .put("quietNotification", quietNotification).put("disguise", disguise)

    companion object {
        const val DPI_AUTO = "auto"
        const val ANY_NETWORK = "*"
        val DNS_PRESETS = listOf("Cloudflare" to "https://1.1.1.1/dns-query", "Google" to "https://8.8.8.8/dns-query",
            "Quad9" to "https://9.9.9.9/dns-query", "AdGuard (без рекламы)" to "https://94.140.14.14/dns-query")
        const val DEFAULT_BYEDPI = "-o1 -At,r,s -d1 -At,r,s -f-1 -t8 -n {sni} -Qo"
        private const val IG = "igareck/vpn-configs-for-russia/main"
        val DEFAULT_SUBSCRIPTIONS = listOf(
            "https://raw.githubusercontent.com/$IG/BLACK_VLESS_RUS_mobile.txt",
            "https://raw.githubusercontent.com/$IG/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://raw.githubusercontent.com/$IG/BLACK_VLESS_RUS.txt",
            "https://raw.githubusercontent.com/$IG/BLACK_SS+All_RUS.txt",
        )
        /** Same files on mirrors when raw.githubusercontent.com is blocked. */
        fun mirrors(url: String): List<String> {
            val m = Regex("https://raw\\.githubusercontent\\.com/igareck/vpn-configs-for-russia/main/(.+)").find(url) ?: return listOf(url)
            val f = m.groupValues[1]
            return listOf(url, "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/$f",
                "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/$f",
                "https://cdn.jsdelivr.net/gh/igareck/vpn-configs-for-russia@main/$f")
        }
        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            fun list(k: String, def: List<String>) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: def
            return Settings(
                mode = runCatching { Mode.valueOf(o.getString("mode")) }.getOrDefault(d.mode),
                balance = runCatching { Balance.valueOf(o.getString("balance")) }.getOrDefault(d.balance),
                socksPort = o.optInt("socksPort", d.socksPort).takeIf { it in 1024..65535 } ?: d.socksPort,
                ruDirect = o.optBoolean("ruDirect", d.ruDirect), blockAds = o.optBoolean("blockAds", d.blockAds),
                byeDpiArgs = o.optString("byeDpiArgs", d.byeDpiArgs), byeDpiSni = o.optString("byeDpiSni", d.byeDpiSni),
                hybridDomains = list("hybridDomains", d.hybridDomains), testUrl = o.optString("testUrl", d.testUrl),
                dnsUrl = o.optString("dnsUrl", d.dnsUrl), subscriptions = list("subscriptions", d.subscriptions),
                dpiStrategy = o.optString("dpiStrategy", d.dpiStrategy),
                dpiRemembered = o.optJSONObject("dpiRemembered")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap(),
                stealthSocks = o.optBoolean("stealthSocks", d.stealthSocks), blockStun = o.optBoolean("blockStun", d.blockStun),
                blockQuic = o.optBoolean("blockQuic", d.blockQuic), mux = o.optBoolean("mux", d.mux), rotateMasks = o.optBoolean("rotateMasks", d.rotateMasks), ruDns = o.optBoolean("ruDns", d.ruDns),
                apps = list("apps", d.apps), onlyApps = o.optBoolean("onlyApps", d.onlyApps),
                quietNotification = o.optBoolean("quietNotification", d.quietNotification), disguise = o.optString("disguise", d.disguise),
            )
        }
    }
}
