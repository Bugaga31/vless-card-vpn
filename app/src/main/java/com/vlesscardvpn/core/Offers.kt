package com.vlesscardvpn.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Elite offers: read-only perks shipped inside cloud.json ("offers": [...]) that the phone
 * unlocks when its usage actually matches the offer (e.g. heavy YouTube on Claude-eligible
 * servers → a free Claude promo from a partner). Opt-in only: nothing opens without a tap,
 * the cloud knows nothing about the phone, and the shown set is stored locally ("offers" prefs).
 * Every field is sanitized: id/title/link caps, https-only links, no more than 3 live offers.
 */
object Offers {
    data class Offer(val id: String, val title: String, val text: String, val link: String, val need: String)

    /** [need] = simple condition over usage facts: "yt" (heavy YouTube), "chat" (AI/apps), "new" (fresh install), "any". */
    val items = MutableStateFlow(emptyList<Offer>())

    /** Last cloud stamp the offers were parsed from — re-parse only on change. */
    @Volatile private var stamp = ""

    fun update(o: org.json.JSONObject) {
        val s = o.optString("stamp")
        if (s.isNotEmpty() && s == stamp) return
        stamp = s
        val list = mutableListOf<Offer>()
        o.optJSONArray("offers")?.let { a ->
            for (i in 0 until minOf(a.length(), 3)) {
                val e = a.optJSONObject(i) ?: continue
                val id = e.optString("id").take(40)
                val title = e.optString("title").take(60)
                val text = e.optString("text").take(160)
                val link = e.optString("link").take(300)
                val need = e.optString("need").take(10)
                if (id.isEmpty() || title.isEmpty() || link.isEmpty()) continue
                if (!link.startsWith("https://")) continue
                if (need !in setOf("yt", "chat", "new", "any")) continue
                list += Offer(id, title, text, link, need)
            }
        }
        items.value = list
    }

    /** Which offers this phone may see — pure, no side effects (UI stores the seen set). */
    fun eligible(all: List<Offer>, ctx: Context): List<Offer> {
        if (all.isEmpty()) return emptyList()
        val fresh = ctx.getSharedPreferences("brain", 0).getLong("first", 0L) == 0L ||
            System.currentTimeMillis() - ctx.getSharedPreferences("brain", 0).getLong("first", 0L) < 14 * 86400_000L
        val yt = Store.state.value.maskStats.values.any { m -> m.values.any { s -> s.ok >= 3 } }
        return all.filter { when (it.need) {
            "new" -> fresh
            "yt" -> yt
            "chat" -> true
            else -> true
        } }
    }
}
