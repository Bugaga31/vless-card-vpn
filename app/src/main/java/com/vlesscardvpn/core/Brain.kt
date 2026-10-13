package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.json.JSONArray
import org.json.JSONObject

/** Small online logistic model (14 features). Mobile/Wi-Fi learn separately; real probes are authoritative.
 * Cloud weights are optional hints, not code or instructions. They cannot overwrite an already learned model.
 */
object Brain {
    const val N = 14
    private val w = HashMap<String, DoubleArray>()
    private val trained = HashMap<String, Double>()
    private var cloudSig = ""
    @Volatile var seen = 0; private set
    private var dirty = false

    fun features(s: Server, st: ServerState): DoubleArray {
        val good = st.okCount.coerceAtLeast(0).toDouble(); val bad = st.failCount.coerceAtLeast(0).toDouble()
        val tries = good + bad
        val h = hour()
        return doubleArrayOf(
            1.0,
            if (st.tcpMs > 0) minOf(st.tcpMs, 2000) / 1000.0 else 2.0,
            if (tries > 0) good / tries else 0.5,
            minOf(tries, 20.0) / 20.0,
            if (st.realMs > 0) 1.0 else if (st.realMs == 0) -1.0 else 0.0,
            when (st.bigOk) { true -> 1.0; false -> -1.0; null -> 0.0 },
            if (s.security == "reality") 1.0 else 0.0,
            if (Revive.cdnLike(s)) 1.0 else 0.0,
            if (s.protocol == "wireguard" || s.protocol == "hysteria2") 1.0 else 0.0,
            if (st.maskId.isNotEmpty()) 1.0 else 0.0,
            if (s.port == 443) 1.0 else 0.0,
            ((System.currentTimeMillis() - st.checkedAt.coerceAtLeast(0)).coerceAtLeast(0) / 86_400_000.0).coerceAtMost(3.0) / 3.0,
            kotlin.math.sin(h * Math.PI / 12), kotlin.math.cos(h * Math.PI / 12),
        )
    }
    private fun hour(): Double = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) + it.get(java.util.Calendar.MINUTE) / 60.0 }
    private fun kind(net: String) = if (net.startsWith("Моб.")) "m" else "w"
    private fun prior() = DoubleArray(N).also { it[4] = 1.5; it[2] = 1.0; it[1] = -0.5; it[5] = 0.5 }
    private fun weights(net: String) = w.getOrPut(kind(net)) { prior() }
    private fun probability(k: DoubleArray, x: DoubleArray): Double {
        var z = 0.0; for (i in 0 until N) z += k[i] * x[i]
        return 1 / (1 + kotlin.math.exp(-z.coerceIn(-30.0, 30.0)))
    }
    @Synchronized fun p(net: String, s: Server, st: ServerState): Double = probability(weights(net), features(s, st))

    fun learningRate(checks: Double): Double = maxOf(0.01, 0.2 / kotlin.math.sqrt(1.0 + checks.coerceAtLeast(0.0) / 50.0))
    @Synchronized fun learn(net: String, s: Server, st: ServerState, works: Boolean, weight: Double = 1.0) {
        if (!weight.isFinite() || weight <= 0) return
        val importance = weight.coerceAtMost(1.0)
        val x = features(s, st); val k = weights(net); val family = kind(net)
        // Exactly the same feature vector is used for prediction and the gradient.
        val err = (if (works) 1.0 else 0.0) - probability(k, x)
        val checks = trained[family] ?: 0.0
        val lr = learningRate(checks) * importance
        for (i in 0 until N) k[i] = (k[i] + lr * err * x[i] - 0.0005 * importance * k[i]).coerceIn(-8.0, 8.0)
        trained[family] = (checks + importance).coerceAtMost(10_000_000.0)
        seen = (seen + 1).coerceAtMost(10_000_000); dirty = true
    }
    /** A balancer result cannot label every member. Only one known server gets a weak live observation. */
    fun lived(net: String, used: List<Server>, state: (Server) -> ServerState, works: Boolean) {
        val one = used.distinctBy { it.id }.singleOrNull() ?: return
        runCatching { learn(net, one, state(one), works, weight = 0.2) }
    }
    fun order(net: String, list: List<Server>, state: (Server) -> ServerState): List<Server> =
        list.sortedWith(compareByDescending<Server> { (p(net, it, state(it)) * 10).toInt() }.thenBy { state(it).score }
            .thenBy { state(it).tcpMs.let { t -> if (t > 0) t else Int.MAX_VALUE } })

    /** Reject malformed / non-finite / out-of-range values instead of poisoning all future predictions. */
    fun vector(a: JSONArray?): DoubleArray? {
        if (a == null || a.length() != N) return null
        val out = DoubleArray(N)
        for (i in 0 until N) {
            val n = a.opt(i) as? Number ?: return null
            val v = n.toDouble(); if (!v.isFinite() || v !in -8.0..8.0) return null
            out[i] = v
        }
        return out
    }
    @Synchronized fun snapshot(): String = JSONObject().put("seen", seen).put("trained", JSONObject(trained as Map<*, *>))
        .put("cloudSig", cloudSig).apply { w.forEach { (k, v) -> put(k, JSONArray(v.toList())) } }.toString()

    /** Atomic model import: validate the whole payload before replacing any weights. */
    @Synchronized fun restore(text: String): Boolean = runCatching {
        if (text.length > 32_000) return@runCatching false
        val o = JSONObject(text); val models = HashMap<String, DoubleArray>(); val counts = HashMap<String, Double>()
        val total = o.optLong("seen", 0).coerceIn(0, 10_000_000).toInt()
        listOf("m", "w").forEach { k ->
            if (o.has(k)) models[k] = vector(o.optJSONArray(k)) ?: return@runCatching false
            // Older files had one global counter: preserve their conservative learning rate on migration.
            val n = o.optJSONObject("trained")?.optDouble(k, 0.0) ?: total.toDouble()
            if (!n.isFinite() || n < 0 || n > 10_000_000) return@runCatching false
            counts[k] = n
        }
        w.clear(); w.putAll(models); trained.clear(); trained.putAll(counts)
        seen = total; cloudSig = o.optString("cloudSig").take(64); dirty = true
        true
    }.getOrDefault(false)

    @Synchronized fun save(ctx: android.content.Context) {
        if (!dirty) return
        ctx.getSharedPreferences("brain", 0).edit().putString("w", snapshot()).apply()
        dirty = false
    }
    @Synchronized fun load(ctx: android.content.Context) = runCatching {
        if (w.isNotEmpty()) return@runCatching
        ctx.getSharedPreferences("brain", 0).getString("w", null)?.let { restore(it) }
    }
    /** Small, bounded cloud nudge, once per distinct model content (not once per changing cloud stamp). */
    @Synchronized fun blend(o: JSONObject, phones: Int) {
        if (phones < 1) return
        val models = listOf("m", "w").mapNotNull { k -> vector(o.optJSONArray(k))?.let { k to it } }.toMap()
        if (models.isEmpty()) return
        val text = models.keys.sorted().joinToString("|") { it + ":" + models.getValue(it).joinToString(",") }
        val sig = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        if (sig == cloudSig) return
        models.forEach { (k, c) ->
            val mine = w[k] ?: prior()
            val alpha = 0.2 * phones.toDouble() / (phones.toDouble() + 20.0) * if ((trained[k] ?: 0.0) >= 50) 0.5 else 1.0
            w[k] = DoubleArray(N) { (1 - alpha) * mine[it] + alpha * c[it] }
        }
        cloudSig = sig; dirty = true
    }
    @Synchronized fun clear() { w.clear(); trained.clear(); seen = 0; cloudSig = ""; dirty = false }
    @Synchronized fun reload(ctx: android.content.Context) { clear(); load(ctx) }
    fun report(): String = if (seen < 20) "Мини-нейросеть только учится (наблюдений: $seen)." else "Мини-нейросеть использовала $seen наблюдений — подсказывает, какие запасные пробовать первыми."
}
