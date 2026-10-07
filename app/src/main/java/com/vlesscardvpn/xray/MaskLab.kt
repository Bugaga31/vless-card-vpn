package com.vlesscardvpn.xray

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * «Эволюция масок» (VLESS Card only): the TSPU learns fixed fragment sizes and noise shapes, so a built-in list
 * goes stale. Every mask search also tries a few mutants of masks that already passed on this network (other
 * piece sizes, pauses, ladder steps, fingerprint, noise shape, port hopping). Mutants that pass are kept
 * (Settings.autoMasks) and become parents of the next generation, so the masks drift away from what the DPI knows.
 * Without parents (a fresh install or nothing passed yet), random shapes are generated.
 */
object MaskLab {
    const val PREFIX = "auto:"
    const val CAP = 60

    private val RANGE = Regex("^(\\d{1,4})(?:-(\\d{1,4}))?$")

    private fun parse(r: String): Pair<Int, Int>? = RANGE.matchEntire(r.trim())?.let { m ->
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt(); minOf(a, b) to maxOf(a, b)
    }

    /** "a-b" scaled by a random factor (0.5…1.8), kept inside [lo]..[hi]. */
    fun jitter(r: String, rnd: Random, lo: Int, hi: Int): String {
        val (a, b) = parse(r) ?: return r
        val f = 0.5 + rnd.nextDouble() * 1.3
        val na = (a * f + rnd.nextInt(-1, 2)).toInt().coerceIn(lo, hi)
        val nb = (b * f + rnd.nextInt(-1, 3)).toInt().coerceIn(na, hi)
        return "$na-$nb"
    }

    private fun range(rnd: Random, lo: Int, hi: Int, maxSpan: Int): String {
        val a = rnd.nextInt(lo, hi + 1); val b = (a + rnd.nextInt(0, maxSpan + 1)).coerceAtMost(hi); return "$a-$b"
    }

    /** A random ladder of 3-9 steps: small pieces around the SNI, bigger ones at the ends. */
    private fun ladder(rnd: Random): Pair<String, String> {
        val n = rnd.nextInt(3, 10)
        val ls = List(n) { i -> if (i == 0 && rnd.nextBoolean()) range(rnd, 20, 120, 40) else if (i == n - 1) range(rnd, 20, 400, 200) else range(rnd, 1, 12, 6) }
        val ds = List(n) { range(rnd, 1, 20, 10) }
        return ls.joinToString(",") to ds.joinToString(",")
    }

    private fun randomNoise(rnd: Random): String = JSONArray().apply {
        repeat(rnd.nextInt(2, 9)) { put(JSONObject().put("rand", if (rnd.nextInt(5) == 0) range(rnd, 300, 1200, 300) else range(rnd, 8, 120, 60)).put("delay", range(rnd, 1, 12, 6))) }
    }.toString()

    /** Mutated noise items: other sizes / pauses, a packet more or less. Protocol look-alike packets stay byte-exact. */
    private fun mutateNoise(json: String, rnd: Random): String {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return randomNoise(rnd)
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { JSONObject(it.toString()) }.toMutableList()
        items.forEach { o ->
            if (o.has("rand") && rnd.nextInt(3) > 0) o.put("rand", jitter(o.getString("rand"), rnd, 1, 1400))
            if (rnd.nextInt(3) > 0) o.put("delay", jitter(o.optString("delay", "1-3"), rnd, 0, 60))
        }
        when (rnd.nextInt(4)) {
            0 -> if (items.size < 14) items.add(rnd.nextInt(items.size + 1), JSONObject().put("rand", range(rnd, 8, 120, 60)).put("delay", range(rnd, 1, 8, 4)))
            1 -> if (items.size > 1) items.removeAt(rnd.nextInt(items.size))
            2 -> if (items.size < 14) items.add(JSONObject(items[rnd.nextInt(items.size)].toString()))
        }
        return JSONArray(items.take(16)).toString()
    }

    /** One mutant of [p] (a mask that worked); null when the result is invalid. */
    fun mutate(p: Mask, rnd: Random): Mask? = runCatching {
        val o = MyMasks.toJson(p)
        val udp = p.hasNoise || p.hop.isNotEmpty()
        if (udp) {
            val nj = p.noiseJson.ifEmpty { Masks.noiseFor(p) ?: randomNoise(rnd) }
            o.remove("n"); o.put("nj", mutateNoise(nj, rnd))
            if (Masks.WG_NOISES.any { it[0] == p.noise } || p.hop.isNotEmpty()) {
                // WireGuard only: hopping on/off / local ↔ WARP ports
                if (rnd.nextInt(4) == 0) when (p.hop) { "" -> o.put("h", Masks.HOP_LOCAL); Masks.HOP_LOCAL -> o.put("h", Masks.HOP_WARP); else -> o.remove("h") }
                else if (p.hop.isNotEmpty()) o.put("h", p.hop)
            }
        } else {
            if (p.packets.isEmpty() || rnd.nextInt(6) == 0) {
                o.put("p", "tlshello"); val (ls, ds) = ladder(rnd); o.put("ls", ls).put("ds", ds); o.remove("l"); o.remove("d")
            } else if (p.lengths.isNotEmpty()) {
                val ls = p.lengths.split(',').toMutableList(); val ds = p.delays.split(',').toMutableList()
                for (i in ls.indices) if (rnd.nextInt(3) > 0) { ls[i] = jitter(ls[i], rnd, 1, 1400); ds[i] = jitter(ds.getOrElse(i) { "1-2" }, rnd, 0, 200) }
                when (rnd.nextInt(4)) {
                    0 -> if (ls.size < 14) { val i = rnd.nextInt(ls.size + 1); ls.add(i, range(rnd, 1, 6, 3)); ds.add(i, range(rnd, 1, 8, 4)) }
                    1 -> if (ls.size > 2) { val i = rnd.nextInt(ls.size); ls.removeAt(i); ds.removeAt(i) }
                    2 -> { val i = rnd.nextInt(ls.size); val j = rnd.nextInt(ls.size); ls[i] = ls[j].also { ls[j] = ls[i] } }
                }
                o.put("ls", ls.joinToString(",")).put("ds", ds.take(ls.size).joinToString(","))
            } else {
                if (rnd.nextInt(4) == 0) { val (ls, ds) = ladder(rnd); o.put("ls", ls).put("ds", ds); o.remove("l"); o.remove("d") }
                else { o.put("l", jitter(p.length, rnd, 1, 1400)).put("d", jitter(p.delay, rnd, 0, 200)) }
                if (p.packets != "tlshello" && rnd.nextInt(4) == 0) o.put("p", "tlshello")
            }
            if (rnd.nextInt(5) == 0) o.put("fp", Masks.FINGERPRINTS[rnd.nextInt(Masks.FINGERPRINTS.size)])
            if (rnd.nextInt(8) == 0) { if (p.dpi == Masks.CURRENT_DPI) o.remove("dpi") else o.put("dpi", Masks.CURRENT_DPI) }
        }
        val gen = p.title.substringAfter("поколение ", "").substringBefore(')').toIntOrNull() ?: 0
        o.put("t", "Авто: " + kind(o) + " (поколение ${gen + 1})")
        make(o)
    }.getOrNull()

    /** A random mask for TCP ([udp] = false) or UDP servers. */
    fun random(rnd: Random, udp: Boolean): Mask? = runCatching {
        val o = JSONObject().put("fp", "chrome")
        if (udp) o.put("nj", randomNoise(rnd))
        else { val (ls, ds) = ladder(rnd); o.put("fp", listOf("chrome", "firefox", "safari")[rnd.nextInt(3)]).put("p", "tlshello").put("ls", ls).put("ds", ds) }
        o.put("t", "Авто: " + kind(o) + " (поколение 1)")
        make(o)
    }.getOrNull()

    private fun kind(o: JSONObject): String = when {
        o.has("nj") -> "шум ${runCatching { JSONArray(o.getString("nj")).length() }.getOrDefault(0)} пак." + if (o.has("h")) " + порты" else ""
        o.has("ls") -> "лесенка ${o.getString("ls").split(',').size} ступ."
        o.has("p") -> "дробление ${o.optString("l")}"
        else -> "отпечаток"
    }

    /** Validated (same rules as «Мои маскировки»), id from the parameters: the same shape bred twice is one mask. */
    fun make(o: JSONObject): Mask { val m = MyMasks.fromJson(o); return m.copy(id = PREFIX + MyMasks.idOf(m.copy(custom = false)), custom = false, auto = true) }

    fun store(m: Mask): String = MyMasks.toJson(m).toString()
    fun load(stored: List<String>): List<Mask> = stored.mapNotNull { runCatching { make(JSONObject(it)) }.getOrNull() }.distinctBy { it.id }

    /**
     * Candidates for one search: [n] mutants of [parents] (best first get more children) and some random newcomers,
     * without masks already known. Same [seed] → same pool (tests).
     */
    fun breed(parents: List<Mask>, n: Int, known: Set<String>, seed: Long = System.nanoTime()): List<Mask> {
        val rnd = Random(seed)
        val out = LinkedHashMap<String, Mask>()
        var guard = 0
        while (out.size < n && guard++ < n * 8) {
            val m = if (parents.isEmpty() || rnd.nextInt(5) == 0) random(rnd, udp = rnd.nextInt(3) == 0)
                else mutate(parents[(rnd.nextDouble().let { it * it } * parents.size).toInt().coerceAtMost(parents.size - 1)], rnd)
            if (m != null && m.id !in known) out.putIfAbsent(m.id, m)
        }
        return out.values.toList()
    }

    /** Settings.autoMasks after a search: passed mutants in front, the list capped at [CAP]. */
    fun keep(stored: List<String>, winners: List<Mask>): List<String> {
        val old = load(stored)
        return (winners + old).distinctBy { it.id }.take(CAP).map { store(it) }
    }
}
