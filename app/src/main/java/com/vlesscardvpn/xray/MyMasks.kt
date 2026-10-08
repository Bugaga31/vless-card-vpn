package com.vlesscardvpn.xray

import com.vlesscardvpn.model.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Мои маскировки»: masks the user builds or receives from friends as a vcmask:// link. A link carries only
 * the mask parameters (no servers, no keys), so it is safe to post in a chat. Same parameters → same id,
 * so importing a link twice does not create duplicates.
 */
object MyMasks {
    const val SCHEME = "vcmask://"
    private val RANGE = Regex("^\\d{1,4}(-\\d{1,4})?$")
    private val LINK = Regex("vcmask://[A-Za-z0-9_\\-=]+(#[^\\s]*)?")
    private val SNI = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val NOISE_KEYS = setOf("rand", "type", "packet", "delay")

    fun toJson(m: Mask): JSONObject = JSONObject().put("t", m.title).put("fp", m.fingerprint)
        .apply {
            if (m.packets.isNotEmpty()) put("p", m.packets)
            if (m.length.isNotEmpty()) put("l", m.length)
            if (m.delay.isNotEmpty()) put("d", m.delay)
            if (m.maxSplit.isNotEmpty() && m.maxSplit != "0") put("ms", m.maxSplit)
            if (m.lengths.isNotEmpty()) put("ls", m.lengths)
            if (m.delays.isNotEmpty()) put("ds", m.delays)
            if (m.noise.isNotEmpty()) put("n", m.noise)
            if (m.noiseJson.isNotEmpty()) put("nj", m.noiseJson)
            if (m.hop.isNotEmpty()) put("h", m.hop)
            if (m.dpi == Masks.CURRENT_DPI) put("dpi", m.dpi)
            if (m.sni.isNotEmpty()) put("sni", m.sni)
            if (m.alpn.isNotEmpty()) put("alpn", m.alpn)
            if (m.mss > 0) put("mss", m.mss)
            if (m.mux) put("mux", true)
            if (m.tfo) put("tfo", true)
            if (m.ipv6) put("v6", true)
        }

    /** Parses and validates; throws IllegalArgumentException with a user-readable message. */
    fun fromJson(o: JSONObject): Mask {
        val title = o.optString("t").trim().take(60).ifEmpty { "Моя маскировка" }
        val fp = o.optString("fp", "chrome").ifEmpty { "chrome" }
        require(fp in Masks.FINGERPRINTS) { "Неизвестный отпечаток: $fp" }
        val packets = o.optString("p")
        require(packets.isEmpty() || packets == "tlshello" || RANGE.matches(packets)) { "Пакеты: «tlshello» или диапазон вида 1-3" }
        val length = o.optString("l"); val delay = o.optString("d")
        val lengths = o.optString("ls"); val delays = o.optString("ds")
        if (packets.isNotEmpty()) {
            if (lengths.isNotEmpty()) {
                val a = lengths.split(','); val b = delays.split(',')
                require(a.all { RANGE.matches(it.trim()) } && b.all { RANGE.matches(it.trim()) }) { "Лесенка: диапазоны через запятую, например 1-1,2-4,5-10" }
                require(a.size == b.size) { "Лесенка: длин ${a.size}, а пауз ${b.size} — должно быть поровну" }
                require(a.size <= 16) { "Лесенка: не больше 16 ступенек" }
            } else require(RANGE.matches(length) && RANGE.matches(delay)) { "Дробление: укажите размер и паузу, например 10-30 и 5-10" }
        }
        val maxSplit = o.optString("ms")
        require(maxSplit.isEmpty() || maxSplit.toIntOrNull()?.let { it in 0..64 } == true) { "Макс. кусков: число 0-64" }
        val noise = o.optString("n")
        require(noise.isEmpty() || Masks.noiseItems(noise) != null) { "Неизвестный шум: $noise" }
        val nj = o.optString("nj").trim()
        if (nj.isNotEmpty()) {
            val a = runCatching { JSONArray(nj) }.getOrNull() ?: throw IllegalArgumentException("Свой шум: нужен JSON-массив [{\"rand\":\"10-60\",\"delay\":\"5-10\"}]")
            require(a.length() in 1..16) { "Свой шум: от 1 до 16 пакетов" }
            for (i in 0 until a.length()) {
                val it = a.optJSONObject(i) ?: throw IllegalArgumentException("Свой шум: каждый пакет — объект {…}")
                require(it.keys().asSequence().all { k -> k in NOISE_KEYS }) { "Свой шум: допустимы поля rand, type, packet, delay" }
                require(it.has("rand") || it.has("packet")) { "Свой шум: у пакета нужен rand или packet" }
            }
        }
        val hop = o.optString("h")
        require(hop.isEmpty() || hop == Masks.HOP_LOCAL || hop == Masks.HOP_WARP) { "Неизвестный режим смены порта" }
        val dpi = o.optString("dpi").takeIf { it == Masks.CURRENT_DPI }.orEmpty()
        require(!(packets.isNotEmpty() && (noise.isNotEmpty() || nj.isNotEmpty() || hop.isNotEmpty()))) {
            "Дробление (TCP) и шум/порты (UDP) — для разных серверов: сделайте две маскировки"
        }
        val sni = o.optString("sni").trim().lowercase()
        require(sni.isEmpty() || SNI.matches(sni)) { "SNI: домен вида vk.com" }
        val alpn = o.optString("alpn").replace(" ", "")
        require(alpn.isEmpty() || alpn in Masks.ALPNS) { "ALPN: h2,http/1.1 или http/1.1" }
        val mss = o.optInt("mss", 0)
        require(mss == 0 || mss in 88..1460) { "MSS: от 88 до 1460" }
        val mux = o.optBoolean("mux", false)
        val tfo = o.optBoolean("tfo", false); val v6 = o.optBoolean("v6", false)
        require(!((mss > 0 || mux || tfo || sni.isNotEmpty()) && (noise.isNotEmpty() || nj.isNotEmpty() || hop.isNotEmpty()))) {
            "SNI, MSS и «один поток» — для TCP-серверов, шум — для UDP: сделайте две маскировки"
        }
        val norm0 = Mask("", title, fp, packets, if (lengths.isEmpty()) length else "", if (lengths.isEmpty()) delay else "",
            maxSplit.ifEmpty { "0" }, dpi, lengths.replace(" ", ""), delays.replace(" ", ""), noise, hop,
            if (nj.isEmpty()) "" else JSONArray(nj).toString(), custom = true)
        val norm = norm0.copy(sni = sni, alpn = alpn, mss = mss, mux = mux, tfo = tfo, ipv6 = v6)
        return norm.copy(id = "my:" + idOf(norm))
    }

    /** Stable id from the parameters (not the title): renaming keeps learned statistics. */
    fun idOf(m: Mask): String = toJson(m.copy(title = "")).toString().let { s ->
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(5).joinToString("") { "%02x".format(it) }
    }

    fun encode(m: Mask): String = SCHEME + Base64.encode(toJson(m).toString().toByteArray()).replace('+', '-').replace('/', '_').trimEnd('=') +
        "#" + java.net.URLEncoder.encode(m.title, "UTF-8")

    fun decode(link: String): Mask {
        val body = link.trim().removePrefix(SCHEME).substringBefore('#')
        val json = Base64.decodeToString(body) ?: throw IllegalArgumentException("Ссылка повреждена")
        return fromJson(runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("Ссылка повреждена") })
    }

    /** Every vcmask:// link in a pasted text (a chat message can carry several). Broken ones are skipped. */
    fun parseLinks(text: String): List<Mask> = LINK.findAll(text).mapNotNull { runCatching { decode(it.value) }.getOrNull() }.distinctBy { it.id }.toList()

    /** Settings.myMasks (JSON strings) → masks; invalid entries are dropped. */
    fun load(stored: List<String>): List<Mask> = stored.mapNotNull { runCatching { fromJson(JSONObject(it)) }.getOrNull() }.distinctBy { it.id }

    fun store(m: Mask): String = toJson(m).toString()

    /** A starting point for the editor: copy of any mask (built-in or own) as an own one. */
    fun fork(m: Mask, title: String = m.title): Mask = fromJson(toJson(m.copy(title = title)))
}
