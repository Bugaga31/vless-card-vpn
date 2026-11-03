package com.vlesscardvpn.core

/** Endpoint reachability, not a guarantee that a signed-in service account is eligible in that country. */
object ServiceRoutes {
    data class Target(val id: String, val title: String, val url: String, val domains: List<String>, val ai: Boolean = false, val ips: List<String> = emptyList())
    val targets = listOf(
        Target("calls", "Голосовые звонки UDP", "https://www.gstatic.com/generate_204",
            Services.domains(listOf("whatsapp", "telegram", "discord")), ips = Services.ips(listOf("telegram"))),
        Target("youtube", "Видео YouTube", "https://www.gstatic.com/generate_204", listOf("geosite:youtube", "domain:googlevideo.com", "domain:ytimg.com", "domain:ggpht.com", "domain:youtube.com", "domain:youtu.be")),
        Target("telegram", "Telegram MTProto", "https://www.gstatic.com/generate_204", Services.domains(listOf("telegram")), ips = Services.ips(listOf("telegram"))),
        Target("gemini", "Gemini", "https://gemini.google.com/", listOf("domain:gemini.google.com", "domain:generativelanguage.googleapis.com", "domain:aistudio.google.com", "domain:ai.google.dev", "domain:notebooklm.google", "domain:notebooklm.google.com"), true),
        Target("chatgpt", "ChatGPT", "https://chatgpt.com/", listOf("domain:chatgpt.com", "domain:openai.com", "domain:oaistatic.com", "domain:oaiusercontent.com"), true),
        Target("claude", "Claude", "https://claude.ai/", listOf("domain:claude.ai", "domain:anthropic.com"), true),
        Target("discord", "Discord", "https://discord.com/", listOf("domain:discord.com", "domain:discord.gg", "domain:discordapp.com", "domain:discordapp.net", "domain:discord.media", "geosite:discord")),
        Target("whatsapp", "WhatsApp", "https://web.whatsapp.com/", Services.domains(listOf("whatsapp"))),
        Target("spotify", "Spotify", "https://open.spotify.com/", listOf("domain:spotify.com", "domain:scdn.co")))
    fun native(target: Target) = target.id in setOf("youtube", "telegram", "calls")
    fun targetsFor(services: List<String>): List<Target> {
        val requested = services.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
        val active = requested.ifEmpty { setOf("youtube", "telegram") }
        val withCalls = active + if (active.any { it in setOf("whatsapp", "telegram", "discord") }) setOf("calls") else emptySet()
        return targets.filter { it.id in withCalls }
    }
    /** Positive = measured pass, zero = explicit fail, null = inconclusive; never pin an HTML-only pass. */
    fun measurement(target: Target, p: Probe): Int? = if (!p.works) 0 else when (target.id) {
        "youtube" -> when (p.ytOk) { true -> if (p.ytKbps > 0) (10000000L / p.ytKbps).toInt().coerceAtLeast(1) else null; false -> 0; null -> null }
        "telegram" -> when (p.tgOk) { true -> p.realMs.coerceAtLeast(1); false -> 0; null -> null }
        "calls" -> when (p.callsOk) { true -> p.realMs.coerceAtLeast(1); false -> 0; null -> null }
        else -> if (p.works) p.realMs else 0
    }
    private data class Entry(val ids: List<String>, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    fun select(results: List<Pair<String, Int>>, previous: String? = null, single: Boolean = false): List<String> {
        val good = results.filter { it.second > 0 }.distinctBy { it.first }.sortedBy { it.second }
        val first = good.firstOrNull() ?: return emptyList()
        val old = good.firstOrNull { it.first == previous && it.second <= first.second * 1.3 + 50 }
        val chosen = old ?: first
        return if (single) listOf(chosen.first) else (listOf(chosen) + good).distinctBy { it.first }.take(2).map { it.first }
    }
    internal fun measuredChoices(id: String, results: List<Pair<String, Int>>, previous: String? = null): List<String> {
        // Video costs are inverse measured media throughput. Do not blend a 1-Mbit route into a 20-Mbit pair.
        val best = results.filter { it.second > 0 }.minOfOrNull { it.second }
        val comparable = if (id == "youtube" && best != null)
            results.filter { it.second > 0 && it.second.toLong() * 10 <= best.toLong() * 13 }
        else results
        return select(comparable, previous, single = id == "calls")
    }
    fun update(net: String, id: String, results: List<Pair<String, Int>>, now: Long): Boolean {
        val key = "$net|$id"; val previous = cache[key]
        val pick = measuredChoices(id, results, previous?.ids?.firstOrNull())
        // No successful endpoint: drop the pin; ordinary VPN fallback and service repair remain available.
        if (pick.isEmpty()) return cache.remove(key) != null
        cache[key] = Entry(pick, now)
        return pick != previous?.ids
    }
    fun snapshot(net: String, now: Long): Map<String, List<String>> = targets.mapNotNull { t ->
        cache["$net|${t.id}"]?.takeIf { now >= it.at && now - it.at < 25 * 60000L }?.let { t.id to it.ids }
    }.toMap()
    fun indices(ids: List<String>, routes: Map<String, List<String>>): Map<String, List<Int>> = targets.mapNotNull { t ->
        routes[t.id]?.mapNotNull { id -> ids.indexOf(id).takeIf { it >= 0 } }?.distinct()?.take(if (t.id == "calls") 1 else 2)?.takeIf { it.isNotEmpty() }?.let { t.id to it }
    }.toMap()
}
