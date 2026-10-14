package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.model.Server
import org.json.JSONObject

/**
 * Real download speed of servers measured by the cloud (GitHub, every 10 min): `servers/speed.json`,
 * server key ([Report.serverKey]) → kbit/s. «Авто» tests the cloud-fast ones next to the nearest by ping,
 * so a fast server a bit farther away gets its chance; the phone's own test still decides.
 */
object CloudSpeed {
    val status = kotlinx.coroutines.flow.MutableStateFlow("Облако: подсказки ещё не загружены")
    const val URL = "https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/speed.json"
    @Volatile var kbps: Map<String, Int> = emptyMap()
    private var fetchedAt = 0L
    private var attemptedAt = 0L

    fun clear() { kbps = emptyMap(); net = emptyMap(); netFam = ""; fetchedAt = 0; attemptedAt = 0 }

    fun parse(json: String): Map<String, Int> = runCatching {
        JSONObject(json).getJSONObject("kbps").let { o -> o.keys().asSequence().associateWith { o.optInt(it) }.filterValues { it > 0 } }
    }.getOrDefault(emptyMap())

    /**
     * From phones on the current operator (cloud.json "net"): server key → [kbit/s, % of checks that worked, checks].
     * Fresher and more relevant than the GitHub speed: a server fast in a datacenter may be blocked on mobile.
     */
    @Volatile var net: Map<String, IntArray> = emptyMap()
    @Volatile private var netFam = ""

    fun parseNet(json: String): Map<String, IntArray> = runCatching {
        JSONObject(json).let { o -> o.keys().asSequence().mapNotNull { k -> o.optJSONArray(k)?.takeIf { it.length() >= 3 }
            ?.let { a -> k to intArrayOf(a.optInt(0), a.optInt(1), a.optInt(2)) } }.toMap() }
    }.getOrDefault(emptyMap())

    fun setNet(ctx: Context, fam: String, json: String) {
        ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE).edit().putString("net:$fam", json).apply()
        net = parseNet(json); netFam = fam
    }

    /** One complete snapshot: absent operators must not retain obsolete evidence. */
    fun netForFamily(json: String, fam: String): Map<String, IntArray> = runCatching {
        JSONObject(json).optJSONObject(fam)?.let { parseNet(it.toString()) } ?: emptyMap()
    }.getOrDefault(emptyMap())

    fun cacheNet(ctx: Context, json: String, fam: String) {
        ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE).edit().putString("net_json", json).apply()
        net = netForFamily(json, fam); netFam = fam
    }

    fun loadNet(ctx: Context) {
        val fam = Net.family(Net.key(ctx))
        if (fam == netFam) return
        val p = ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE)
        net = p.getString("net_json", null)?.let { netForFamily(it, fam) }
            ?: p.getString("net:$fam", null)?.let { parseNet(it) } ?: emptyMap()
        netFam = fam
    }

    /** Only sustained failures on this operator justify skipping it; 1–2 unlucky checks do not. */
    fun blocked(s: Server, n: Map<String, IntArray> = net): Boolean = n[Report.serverKey(s)]?.let {
        it.size >= 3 && it[2] >= 5 && ServerChoice.successChance(it[1], it[2]) < 0.25
    } == true

    /** Best known speed: this operator's phones first (if it mostly works there), else the GitHub measurement. */
    fun speedOf(key: String, gh: Map<String, Int> = kbps, n: Map<String, IntArray> = net): Int =
        n[key]?.takeIf { it[0] > 0 && it[1] >= 50 }?.get(0) ?: gh[key] ?: 0

    /** Cloud changes every 10 min. Errors back off for a minute; saved data remain usable offline. */
    fun refreshDue(now: Long, fetched: Long, attempted: Long, hasCache: Boolean): Boolean =
        (!hasCache || now < fetched || now - fetched >= 10 * 60_000L) &&
            (attempted == 0L || now < attempted || now - attempted >= 60_000L)

    /** Memory/disk only: never make «Подключить» wait for a cloud HTTP timeout. */
    fun loadCached(ctx: Context) {
        CloudControl.loadCached(ctx)
        runCatching { loadNet(ctx) }
        if (kbps.isEmpty()) ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE).getString("json", null)?.let { kbps = parse(it) }
        status.value = if (kbps.isNotEmpty() || net.isNotEmpty()) "Облако: используются сохранённые рейтинги; подтверждение на телефоне" else "Облако: кэша рейтингов нет; используем локальное обучение"
    }

    @Synchronized fun refresh(ctx: Context) {
        if (!PhonePower.refresh(ctx).allowLight) return
        runCatching { loadNet(ctx) }
        val p = ctx.getSharedPreferences("cloud_speed", Context.MODE_PRIVATE)
        val cached = p.getString("json", null)
        if (kbps.isEmpty()) cached?.let { kbps = parse(it) }
        if (fetchedAt == 0L) fetchedAt = p.getLong("fetched", 0)
        val now = System.currentTimeMillis()
        if (!refreshDue(now, fetchedAt, attemptedAt, cached != null)) return
        attemptedAt = now
        runCatching {
            val d = CloudTransport.fetch(ctx,"speed.json",if (cached != null) p.getString("etag",null) else null) { JSONObject(it).getJSONObject("kbps") }
            if (d.unchanged && cached != null) {
                fetchedAt=now;p.edit().putLong("fetched",now).apply()
                status.value="Облако: сохранённый рейтинг подтверждён"
            } else if (d.text != null) {
                kbps=parse(d.text);fetchedAt=now
                p.edit().putString("json",d.text).putString("etag",d.etag).putLong("fetched",now).apply()
                status.value="Облако: свежий рейтинг загружен"
            }
        }.onFailure { status.value = "Облако недоступно: используем кэш и локальное обучение" }
    }

    fun prior(s: Server): Double? = CloudControl.prior(s) ?: speedOf(Report.serverKey(s)).takeIf { it > 0 }?.let { (it / 30000.0).coerceIn(.3,.9) }

    /** Up to [n] servers from [pinged] (not in [skip]) the cloud saw download ≥ [min] kbit/s, fastest first. */
    fun pick(pinged: List<Server>, skip: Collection<Server>, n: Int = 10, min: Int = 3000, speeds: Map<String, Int> = kbps,
             here: Map<String, IntArray> = net): List<Server> {
        if (speeds.isEmpty() && here.isEmpty()) return emptyList()
        val ids = skip.map { it.id }.toHashSet()
        return pinged.filter { it.id !in ids && !blocked(it, here) }.map { it to speedOf(Report.serverKey(it), speeds, here) }
            .filter { it.second >= min }.sortedByDescending { it.second }.map { it.first }
            .let { ServerChoice.diverse(it, n) }
    }
}
