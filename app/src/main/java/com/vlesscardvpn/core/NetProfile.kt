package com.vlesscardvpn.core

import com.vlesscardvpn.model.Base64
import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.xray.MaskLab
import com.vlesscardvpn.xray.Masks
import com.vlesscardvpn.xray.MyMasks
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Настройка сети» (vcnet:// link): what this phone learned on one network — the DPI strategies that won
 * (incl. evolved "EVO#" ones) and the masks that passed here. A friend on the same provider imports it and
 * starts with a working setup instead of searching. No servers, keys or addresses inside: safe for a chat.
 */
object NetProfile {
    const val SCHEME = "vcnet://"
    private val LINK = Regex("vcnet://[A-Za-z0-9_\\-=]+(#[^\\s]*)?")
    const val MAX_MASKS = 12

    data class Profile(
        /** Network name of the author ("Моб.: МТС", "Wi-Fi"): shown only, the import goes to the current network. */
        val net: String,
        val dpi: List<String> = emptyList(),
        /** Built-in mask ids. */
        val masks: List<String> = emptyList(),
        /** Own/auto masks, full parameters (they exist only on the author's phone). */
        val extra: List<JSONObject> = emptyList(),
    ) {
        val isEmpty: Boolean get() = dpi.isEmpty() && masks.isEmpty() && extra.isEmpty()
    }

    /** Profile of [network] from the app state: DPI ranking (or the remembered one) and masks with successes, best first. */
    fun build(st: AppState, network: String): Profile {
        val s = st.settings
        val family = Net.family(network)
        val dpi = ((s.dpiRanking[network] ?: s.dpiRanking[family]).orEmpty().let { r ->
            listOfNotNull(s.dpiRemembered[network] ?: s.dpiRemembered[family]) + r
        }).filter { it != "custom" && it != Settings.DPI_AUTO && DpiStrategies.byId(it, s) != null }.distinct().take(5)
        val stats = st.maskStats[network] ?: st.maskStats[family] ?: emptyMap()
        val good = stats.filter { it.value.ok > 0 }.entries
            .sortedWith(compareByDescending<Map.Entry<String, MaskStat>> { it.value.score }.thenByDescending { it.value.ok })
            .mapNotNull { Masks.byId(it.key) }.take(MAX_MASKS)
        val (own, builtIn) = good.partition { it.custom || it.auto }
        return Profile(Net.family(network), dpi, builtIn.map { it.id }, own.map { MyMasks.toJson(it) })
    }

    fun encode(p: Profile): String {
        val o = JSONObject().put("v", 1).put("n", p.net).put("d", JSONArray(p.dpi)).put("m", JSONArray(p.masks))
            .put("x", JSONArray().apply { p.extra.forEach { put(it) } })
        return SCHEME + Base64.encode(o.toString().toByteArray()).replace('+', '-').replace('/', '_').trimEnd('=') +
            "#" + java.net.URLEncoder.encode(p.net, "UTF-8")
    }

    fun decode(link: String): Profile {
        val body = link.trim().removePrefix(SCHEME).substringBefore('#')
        val o = Base64.decodeToString(body)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: throw IllegalArgumentException("Ссылка повреждена")
        fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } } }.orEmpty()
        val x = o.optJSONArray("x")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
        return Profile(o.optString("n", "сеть").take(60), strs("d").take(8), strs("m").take(MAX_MASKS * 2), x.take(MAX_MASKS * 2))
    }

    fun parseLinks(text: String): List<Profile> = LINK.findAll(text).mapNotNull { runCatching { decode(it.value) }.getOrNull() }.toList()

    data class Applied(val dpi: Int, val masks: Int)

    /**
     * [p] applied to [network]: its DPI strategies go first in this network's ranking (and become the current one
     * in auto DPI), its masks get one success here (tried first by the next search), own masks join the auto masks.
     */
    fun apply(st: AppState, p: Profile, network: String): Pair<AppState, Applied> {
        val s = st.settings
        val dpi = p.dpi.filter { DpiStrategies.byId(it, s) != null }
        val extra = p.extra.mapNotNull { runCatching { MaskLab.make(it) }.getOrNull() }
        val maskIds = (p.masks.filter { Masks.byId(it) != null } + extra.map { it.id }).distinct()
        val old = (s.dpiRanking[network] ?: s.dpiRanking[Net.family(network)]).orEmpty()
        val settings = s.copy(
            dpiRanking = if (dpi.isEmpty()) s.dpiRanking else s.dpiRanking + (network to (dpi + old).distinct().take(5)),
            dpiRemembered = if (dpi.isEmpty()) s.dpiRemembered else s.dpiRemembered + (network to dpi.first()),
            autoMasks = if (extra.isEmpty()) s.autoMasks else MaskLab.keep(s.autoMasks, extra),
        )
        val stats = HashMap(st.maskStats[network] ?: st.maskStats[Net.family(network)].orEmpty())
        maskIds.forEach { id -> val c = stats[id] ?: MaskStat(); stats[id] = c.copy(ok = c.ok + 1) }
        val out = st.copy(settings = settings, maskStats = if (maskIds.isEmpty()) st.maskStats else st.maskStats + (network to stats))
        return out to Applied(dpi.size, maskIds.size)
    }
}
