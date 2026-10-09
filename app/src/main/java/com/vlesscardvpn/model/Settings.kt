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
    val blockAds: Boolean = true,
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
    /** «Ускорение телефона»: big TUN MTU (fewer packets = less CPU), stale DNS answers at once, deep sleep with the screen off. */
    val turbo: Boolean = true,
    /** «Сайт не открывается?»: these sites always through the VPN / always directly (domains). */
    val alwaysVpn: List<String> = emptyList(),
    val alwaysDirect: List<String> = emptyList(),
    /** Traffic through the VPN per month ("2025-10" → bytes), the last 3 months; warning above [monthLimitGb] (0 = off). */
    val traffic: Map<String, Long> = emptyMap(),
    val monthLimitGb: Int = 0,
    /** subscription url → what its server reports (subscription-userinfo / profile-title headers). */
    val subInfo: Map<String, SubInfo> = emptyMap(),
    /** Network → servers that worked there last time (picked again when the phone comes back to that network). */
    val netServers: Map<String, List<String>> = emptyMap(),
    /** «Страна»: ISO code — Auto takes servers of this country when some work ("" = any). */
    val country: String = "",
    /** «Избранное»: server ids Auto always includes when they work. */
    val favorites: List<String> = emptyList(),
    /** «Умный YouTube» (Авто): YouTube through the DPI bypass straight to Google when that measured faster than the servers. */
    val smartYoutube: Boolean = true,
    /** network → YouTube through the DPI bypass there (measured; false = servers were faster or it broke). */
    val ytDpi: Map<String, Boolean> = emptyMap(),
    /** Connect by itself after the phone restarts. */
    val autoStart: Boolean = false,
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
        .put("ruAppsDirect", ruAppsDirect).put("randomTun", randomTun).put("coverTraffic", coverTraffic).put("rev", rev).put("turbo", turbo).put("autoStart", autoStart)
        .put("alwaysVpn", JSONArray(alwaysVpn)).put("alwaysDirect", JSONArray(alwaysDirect))
        .put("traffic", JSONObject().apply { traffic.forEach { (k, v) -> put(k, v) } }).put("monthLimitGb", monthLimitGb)
        .put("country", country).put("favorites", JSONArray(favorites)).put("smartYoutube", smartYoutube)
        .put("ytDpi", JSONObject().apply { ytDpi.forEach { (k, v) -> put(k, v) } })
        .put("netServers", JSONObject().apply { netServers.forEach { (k, v) -> put(k, JSONArray(v)) } })
        .put("subInfo", JSONObject().apply { subInfo.forEach { (k, v) -> put(k, v.toJson()) } })

    companion object {
        /** "https://www.Site.com/path" → "site.com"; null when it is not a domain. */
        fun host(input: String): String? = input.trim().lowercase().substringAfter("://").substringBefore('/').substringBefore('?')
            .substringBefore(':').removePrefix("www.").trim('.').takeIf { Regex("^[a-z0-9а-яё-]+(\\.[a-z0-9а-яё-]+)+$").matches(it) }
        const val REV = 72
        /** YouTube video, pictures and player — what «Умный YouTube» sends through the DPI bypass. */
        val YT_DOMAINS = listOf("geosite:youtube", "domain:googlevideo.com", "domain:ytimg.com", "domain:ggpht.com", "domain:youtu.be", "domain:youtube.com")
        /**
         * Once per update, step by step (a later choice of the user is kept): 71 — all masking on (hidden proxy, QUIC off,
         * rotating masks, evolution, behaviour layers, signature masks in the search); 72 — phone speed-up and ad blocking.
         */
        fun migrate(s0: Settings): Settings {
            var s = s0
            if (s.rev < 71) s = s.copy(stealthSocks = true, blockQuic = true, rotateMasks = true,
                maskEvolution = true, autoHeal = true, ruAppsDirect = true, randomTun = true, coverTraffic = true,
                maskFamilies = if (s.maskFamilies.isEmpty()) s.maskFamilies else (s.maskFamilies + "brand" + "auto").distinct())
            if (s.rev < 72) s = s.copy(turbo = true, blockAds = true)
            return if (s.rev >= REV) s else s.copy(rev = REV)
        }
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
                ruAppsDirect = o.optBoolean("ruAppsDirect", true), randomTun = o.optBoolean("randomTun", true), coverTraffic = o.optBoolean("coverTraffic", true), rev = o.optInt("rev", 0), turbo = o.optBoolean("turbo", true), autoStart = o.optBoolean("autoStart", false),
                alwaysVpn = list("alwaysVpn", emptyList()), alwaysDirect = list("alwaysDirect", emptyList()),
                traffic = o.optJSONObject("traffic")?.let { m -> m.keys().asSequence().associateWith { m.optLong(it) } } ?: emptyMap(),
                monthLimitGb = o.optInt("monthLimitGb", 0), country = o.optString("country", ""), favorites = list("favorites", emptyList()),
                smartYoutube = o.optBoolean("smartYoutube", true), ytDpi = o.optJSONObject("ytDpi")?.let { m -> m.keys().asSequence().associateWith { m.optBoolean(it) } } ?: emptyMap(),
                netServers = o.optJSONObject("netServers")?.let { m -> m.keys().asSequence().associateWith { k -> m.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() } } ?: emptyMap(),
                subInfo = o.optJSONObject("subInfo")?.let { m -> m.keys().asSequence().mapNotNull { k -> m.optJSONObject(k)?.let { k to SubInfo.fromJson(it) } }.toMap() } ?: emptyMap(),
            )
        }
    }
}

/** What a paid subscription says about itself: used/total bytes, expiry (unix s), its own name. 0 = unknown. */
data class SubInfo(val used: Long = 0, val total: Long = 0, val expire: Long = 0, val title: String = "", val at: Long = 0) {
    val left: Long get() = (total - used).coerceAtLeast(0)
    fun toJson(): JSONObject = JSONObject().put("used", used).put("total", total).put("expire", expire).put("title", title).put("at", at)
    /** Close to the end: under 10 % of the traffic left or under 3 days. */
    fun ending(nowSec: Long): Boolean = total > 0 && left < total / 10 || expire > 0 && expire - nowSec < 3 * 86400
    companion object {
        fun fromJson(o: JSONObject) = SubInfo(o.optLong("used"), o.optLong("total"), o.optLong("expire"), o.optString("title"), o.optLong("at"))
        /** "upload=1; download=2; total=3; expire=4" + "profile-title" (plain or base64:…). Null when the server says nothing. */
        fun parse(userInfo: String?, title: String?, now: Long = System.currentTimeMillis()): SubInfo? {
            val kv = userInfo.orEmpty().split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() } }.toMap()
            val t = title?.trim().orEmpty().let { if (it.startsWith("base64:")) Base64.decodeToString(it.removePrefix("base64:")).orEmpty() else it }.take(60)
            if (kv.isEmpty() && t.isEmpty()) return null
            fun n(k: String) = kv[k]?.toDoubleOrNull()?.toLong() ?: 0L
            return SubInfo(n("upload") + n("download"), n("total"), n("expire"), t, now)
        }
    }
}
