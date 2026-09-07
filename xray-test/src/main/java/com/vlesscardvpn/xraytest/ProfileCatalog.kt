package com.vlesscardvpn.xraytest

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class CardProfile(val link: String, val name: String, val origin: String, val favorite: Boolean = false) {
    val key: String get() = MessageDigest.getInstance("SHA-256").digest(link.substringBefore('#').toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun node(): Node = XrayConfig.parse(link)
}
data class ParsedProfiles(val profiles: List<CardProfile>, val skipped: Int)
object ProfileCatalog {
    const val LIMIT = 100
    fun parse(input: String, origin: String, decodeBase64: (String) -> String): ParsedProfiles {
        require(input.length <= 2097152) { "Input too large" }
        val text = if (input.contains("://")) input else try { decodeBase64(input.filterNot { it.isWhitespace() }) } catch (_: Exception) { input }
        require(text.length <= 2097152) { "Decoded input too large" }
        val found = mutableListOf<CardProfile>()
        val seen = mutableSetOf<Node>()
        var skipped = 0
        for (rawLine in text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(5000)) {
            if (rawLine.startsWith('#') || rawLine.startsWith("//")) continue
            val line = rawLine.replace("&amp;", "&")
            try {
                val node = XrayConfig.parse(line)
                if (seen.add(node)) {
                    val label = runCatching { URLDecoder.decode(URI(line).rawFragment.orEmpty(), "UTF-8") }.getOrDefault("")
                        .filterNot { it.isISOControl() }.take(80).ifBlank { "VLESS · ${node.host.take(40)}" }
                    found.add(CardProfile(line, label, origin))
                }
            } catch (_: ProfileError) { skipped++ }
        }
        return ParsedProfiles(found, skipped)
    }
    fun merge(existing: List<CardProfile>, incoming: List<CardProfile>): List<CardProfile> =
        mergeBalanced(existing, listOf(incoming))
    fun mergeBalanced(existing: List<CardProfile>, groups: List<List<CardProfile>>): List<CardProfile> {
        val result = existing.distinctBy { it.node() }.take(LIMIT).toMutableList()
        val seen = result.mapTo(mutableSetOf()) { it.node() }
        val iterators = groups.filter { it.isNotEmpty() }.map { it.iterator() }.toMutableList()
        while (result.size < LIMIT && iterators.isNotEmpty()) {
            val round = iterators.iterator()
            while (round.hasNext() && result.size < LIMIT) {
                val source = round.next()
                var added = false
                while (source.hasNext() && !added) {
                    val profile = source.next()
                    if (seen.add(profile.node())) {
                        result.add(profile)
                        added = true
                    }
                }
                if (!source.hasNext()) round.remove()
            }
        }
        return result
    }
    fun candidates(entries: List<CardProfile>, auto: Boolean, favoritesOnly: Boolean, selectedKey: String?): List<CardProfile> =
        if (auto) entries.filter { !favoritesOnly || it.favorite }
        else entries.firstOrNull { it.key == selectedKey }?.let { listOf(it) }.orEmpty()
    fun encode(entries: List<CardProfile>): String = JSONObject().put("version", 1).put("profiles", JSONArray().apply {
        entries.forEach { put(JSONObject().put("link", it.link).put("name", it.name).put("origin", it.origin).put("favorite", it.favorite)) }
    }).toString()
    fun decode(text: String): List<CardProfile> {
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "Unknown storage version" }
        val a = root.getJSONArray("profiles")
        require(a.length() <= LIMIT) { "Too many saved profiles" }
        return (0 until a.length()).map {
            val e = a.getJSONObject(it)
            val link = e.getString("link")
            XrayConfig.parse(link)
            CardProfile(link, e.getString("name"), e.getString("origin"), e.getBoolean("favorite"))
        }
    }
}
