package com.vlesscardvpn.model

import org.json.JSONArray
import org.json.JSONObject

enum class Mode(val title: String) {
    /** Prepares itself (subscriptions → test → masks → DPI search) and picks servers or ByeDPI; heals on failure. */
    AUTO("Авто"), SERVERS("Серверы"), BYEDPI("Без сервера (ByeDPI)"), HYBRID("Гибрид")
}

/** Balancer strategy across several selected servers (Xray routing balancer + observatory). */
enum class Balance(val xray: String, val title: String) {
    LEAST_PING("leastPing", "Самый быстрый"), ROUND_ROBIN("roundRobin", "По очереди"),
    RANDOM("random", "Случайно"), LEAST_LOAD("leastLoad", "Наименее загруженный")
}

data class Settings(
    val mode: Mode = Mode.AUTO,
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
    val blockQuic: Boolean = true,
    val mux: Boolean = false,
    /** Each connect picks a random mask among the ones that passed the last search for that server. */
    val rotateMasks: Boolean = true,
    /** Russian domains resolved by Yandex DNS directly (with RU direct): RU sites see a normal Russian user. */
    val ruDns: Boolean = true,
    /** Package names: excluded from VPN, or (if [onlyApps]) the only ones that use it. */
    val apps: List<String> = emptyList(),
    val onlyApps: Boolean = false,
    val quietNotification: Boolean = false,
    val disguise: String = "",
    /** Only these services (Services ids: youtube, telegram…) go through the VPN; empty = all traffic. */
    val services: List<String> = emptyList(),
    /** After a failed check: other good masks → mask search → (Auto) ByeDPI, reconnecting by itself. */
    val autoHeal: Boolean = true,
    /** Refresh subscriptions on start / before Auto connect when older than 12 h. */
    val autoUpdateSubs: Boolean = true,
    val lastSubRefresh: Long = 0,
    /** Connection type: false = VPN (TUN, all/selected apps), true = proxy only (SOCKS [socksPort] + HTTP [socksPort]+1, no VPN icon). */
    val proxyOnly: Boolean = false,
    /** Proxy mode: listen on 0.0.0.0 so other devices in the Wi-Fi / hotspot can use it. */
    val lanShare: Boolean = false,
    /** false = every app goes through the VPN regardless of [apps]. */
    val perApp: Boolean = true,
    /** Mask families used by the mask search (Masks.FAMILIES ids); empty = all. */
    val maskFamilies: List<String> = emptyList(),
    /** uTLS fingerprints used by the mask search; empty = all. */
    val maskFps: List<String> = emptyList(),
    /** Own WARP+ key(s) (any text: keys are extracted). */
    val warpKeys: String = "",
    /** Try the built-in public WARP+ keys when there is no own key (or it is used up). */
    val warpBuiltinKeys: Boolean = true,
    /** Background: get ready on app start, re-test and switch to faster servers while connected. */
    val autoOptimize: Boolean = true,
    /** WARP+ keys switched off in «Ключи WARP+» (own or built-in): never tried. */
    val warpKeyOff: List<String> = emptyList(),
    /** Key → "ok@time" / "full@time" / "bad@time": what Cloudflare answered last time (see WarpKeys). */
    val warpKeyStatus: Map<String, String> = emptyMap(),
    /** «Мои маскировки»: own masks as JSON (MyMasks.store). */
    val myMasks: List<String> = emptyList(),
    /** «Эволюция масок»: every search also tries mutants of masks that passed (MaskLab). */
    val maskEvolution: Boolean = true,
    /** Auto masks bred by MaskLab that passed somewhere (JSON, newest first, ≤ MaskLab.CAP). */
    val autoMasks: List<String> = emptyList(),
    /** DPI strategies of each network, best first (top 5 of the last search incl. evolved "EVO#" ones): fast fallback. */
    val dpiRanking: Map<String, List<String>> = emptyMap(),
    // ---- masking beyond packets (core/Disguise)
    /** Russian apps (banks, Госуслуги, маркетплейсы…) go around the VPN (VPN mode, unless «Только выбранные»). */
    val ruAppsDirect: Boolean = true,
    /** Random TUN address on every connect and a neutral VPN session name. */
    val randomTun: Boolean = true,
    /** Now and then open an ordinary Russian site directly while connected (looks like a usual user). */
    val coverTraffic: Boolean = true,
    /** Settings revision: older ones get the full masking switched on once (Store.migrate). */
    val rev: Int = REV,
) {
    val httpPort: Int get() = if (socksPort < 65535) socksPort + 1 else socksPort - 1
    fun toJson(): JSONObject = JSONObject().put("mode", mode.name).put("balance", balance.name).put("socksPort", socksPort)
        .put("ruDirect", ruDirect).put("blockAds", blockAds).put("byeDpiArgs", byeDpiArgs).put("byeDpiSni", byeDpiSni)
        .put("hybridDomains", JSONArray(hybridDomains)).put("testUrl", testUrl).put("dnsUrl", dnsUrl)
        .put("subscriptions", JSONArray(subscriptions))
        .put("dpiStrategy", dpiStrategy).put("dpiRemembered", JSONObject(dpiRemembered as Map<*, *>))
        .put("stealthSocks", stealthSocks).put("blockStun", blockStun).put("blockQuic", blockQuic).put("mux", mux).put("rotateMasks", rotateMasks)
        .put("ruDns", ruDns).put("apps", JSONArray(apps)).put("onlyApps", onlyApps)
        .put("quietNotification", quietNotification).put("disguise", disguise)
        .put("services", JSONArray(services)).put("autoHeal", autoHeal).put("autoUpdateSubs", autoUpdateSubs).put("lastSubRefresh", lastSubRefresh)
        .put("proxyOnly", proxyOnly).put("lanShare", lanShare).put("perApp", perApp)
        .put("maskFamilies", JSONArray(maskFamilies)).put("maskFps", JSONArray(maskFps))
        .put("warpKeys", warpKeys).put("warpBuiltinKeys", warpBuiltinKeys).put("autoOptimize", autoOptimize)
        .put("warpKeyOff", JSONArray(warpKeyOff)).put("warpKeyStatus", JSONObject(warpKeyStatus as Map<*, *>)).put("myMasks", JSONArray(myMasks))
        .put("maskEvolution", maskEvolution).put("autoMasks", JSONArray(autoMasks))
        .put("dpiRanking", JSONObject().apply { dpiRanking.forEach { (k, v) -> put(k, JSONArray(v)) } })
        .put("ruAppsDirect", ruAppsDirect).put("randomTun", randomTun).put("coverTraffic", coverTraffic).put("rev", rev)

    companion object {
        const val REV = 71
        /** Once per update: all masking on (hidden proxy, QUIC off, rotating masks, evolution, behaviour layers, signature masks in the search). */
        fun migrate(s: Settings): Settings = if (s.rev >= REV) s else s.copy(rev = REV, stealthSocks = true, blockQuic = true, rotateMasks = true,
            maskEvolution = true, autoHeal = true, ruAppsDirect = true, randomTun = true, coverTraffic = true,
            maskFamilies = if (s.maskFamilies.isEmpty()) s.maskFamilies else (s.maskFamilies + "brand" + "auto").distinct())
        const val DPI_AUTO = "auto"
        const val ANY_NETWORK = "*"
        val DNS_PRESETS = listOf("Cloudflare" to "https://1.1.1.1/dns-query", "Google" to "https://8.8.8.8/dns-query",
            "Quad9" to "https://9.9.9.9/dns-query", "AdGuard (без рекламы)" to "https://94.140.14.14/dns-query")
        const val DEFAULT_BYEDPI = "-o1 -At,r,s -d1 -At,r,s -f-1 -t8 -n {sni} -Qo"
        val DEFAULT_SUBSCRIPTIONS: List<String> get() = Subs.CATALOG.filter { it.default }.map { it.url }
        /** Same file on mirrors when raw.githubusercontent.com is blocked (jsDelivr, githack; igareck also on GitLab/Codeberg). */
        fun mirrors(url: String): List<String> = Subs.mirrors(url)
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
                services = list("services", d.services), autoHeal = o.optBoolean("autoHeal", d.autoHeal),
                autoUpdateSubs = o.optBoolean("autoUpdateSubs", d.autoUpdateSubs), lastSubRefresh = o.optLong("lastSubRefresh", 0),
                proxyOnly = o.optBoolean("proxyOnly", false), lanShare = o.optBoolean("lanShare", false), perApp = o.optBoolean("perApp", true),
                maskFamilies = list("maskFamilies", emptyList()), maskFps = list("maskFps", emptyList()),
                warpKeys = o.optString("warpKeys", ""), warpBuiltinKeys = o.optBoolean("warpBuiltinKeys", true), autoOptimize = o.optBoolean("autoOptimize", true),
                warpKeyOff = list("warpKeyOff", emptyList()),
                warpKeyStatus = o.optJSONObject("warpKeyStatus")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } } ?: emptyMap(),
                myMasks = list("myMasks", emptyList()),
                maskEvolution = o.optBoolean("maskEvolution", true), autoMasks = list("autoMasks", emptyList()),
                dpiRanking = o.optJSONObject("dpiRanking")?.let { m -> m.keys().asSequence().associateWith { k -> m.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() } } ?: emptyMap(),
                ruAppsDirect = o.optBoolean("ruAppsDirect", true), randomTun = o.optBoolean("randomTun", true), coverTraffic = o.optBoolean("coverTraffic", true), rev = o.optInt("rev", 0),
            )
        }
    }
}
