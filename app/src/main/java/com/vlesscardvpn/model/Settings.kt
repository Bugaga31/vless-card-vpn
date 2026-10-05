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
) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode.name).put("balance", balance.name).put("socksPort", socksPort)
        .put("ruDirect", ruDirect).put("blockAds", blockAds).put("byeDpiArgs", byeDpiArgs).put("byeDpiSni", byeDpiSni)
        .put("hybridDomains", JSONArray(hybridDomains)).put("testUrl", testUrl).put("dnsUrl", dnsUrl)
        .put("subscriptions", JSONArray(subscriptions))

    companion object {
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
            )
        }
    }
}
