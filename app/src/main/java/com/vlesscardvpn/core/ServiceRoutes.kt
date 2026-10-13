package com.vlesscardvpn.core

/** Endpoint reachability, not a guarantee that a signed-in service account is eligible in that country. */
object ServiceRoutes {
    data class Target(val id: String, val title: String, val url: String, val domains: List<String>, val ai: Boolean = false)
    val targets = listOf(
        Target("gemini", "Gemini", "https://gemini.google.com/", listOf("domain:gemini.google.com", "domain:generativelanguage.googleapis.com", "domain:aistudio.google.com", "domain:ai.google.dev", "domain:notebooklm.google", "domain:notebooklm.google.com"), true),
        Target("chatgpt", "ChatGPT", "https://chatgpt.com/", listOf("domain:chatgpt.com", "domain:openai.com", "domain:oaistatic.com", "domain:oaiusercontent.com"), true),
        Target("claude", "Claude", "https://claude.ai/", listOf("domain:claude.ai", "domain:anthropic.com"), true),
        Target("spotify", "Spotify", "https://open.spotify.com/", listOf("domain:spotify.com", "domain:scdn.co")))
    private data class Entry(val ids: List<String>, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    fun select(results: List<Pair<String, Int>>, previous: String? = null): List<String> {
        val good = results.filter { it.second > 0 }.distinctBy { it.first }.sortedBy { it.second }
        val first = good.firstOrNull() ?: return emptyList()
        val old = good.firstOrNull { it.first == previous && it.second <= first.second * 1.3 + 50 }
        return (listOfNotNull(old ?: first) + good).distinctBy { it.first }.take(2).map { it.first }
    }
    fun update(net: String, id: String, results: List<Pair<String, Int>>, now: Long): Boolean {
        val key = "$net|$id"; val previous = cache[key]
        val pick = select(results, previous?.ids?.firstOrNull())
        // No successful endpoint: drop the pin; ordinary VPN fallback and service repair remain available.
        if (pick.isEmpty()) return cache.remove(key) != null
        cache[key] = Entry(pick, now)
        return pick != previous?.ids
    }
    fun snapshot(net: String, now: Long): Map<String, List<String>> = targets.mapNotNull { t ->
        cache["$net|${t.id}"]?.takeIf { now >= it.at && now - it.at < 25 * 60000L }?.let { t.id to it.ids }
    }.toMap()
    fun indices(ids: List<String>, routes: Map<String, List<String>>): Map<String, List<Int>> = targets.mapNotNull { t ->
        routes[t.id]?.mapNotNull { id -> ids.indexOf(id).takeIf { it >= 0 } }?.distinct()?.take(2)?.takeIf { it.isNotEmpty() }?.let { t.id to it }
    }.toMap()
}
