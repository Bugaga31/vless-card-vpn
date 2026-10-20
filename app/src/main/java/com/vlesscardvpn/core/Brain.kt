package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import org.json.JSONArray
import org.json.JSONObject

/** Small online logistic model (14 features). Exact network profiles learn separately; real probes are authoritative.
 * Cloud weights are optional hints, not code or instructions. They cannot overwrite an already learned model.
 */
object Brain {
    data class ObservationCount(val count: Int, val networkScoped: Boolean)
    const val N = 14
    private val w = HashMap<String, DoubleArray>()
    private val trained = HashMap<String, Double>()
    private var cloudSig = ""
    private data class LocalModel(val weights: DoubleArray, var checks: Double)
    private val contexts = LinkedHashMap<String, LocalModel>()
    const val MAX_CONTEXTS = 24
    private fun scoped(net: String) = net.contains(" · ")
    private fun contextKey(net: String) = Net.fingerprint("brain-context-v1", net)
    private fun localFeatures(net: String, server: Server, state: ServerState): DoubleArray {
        if (!scoped(net)) return features(server, state)
        val e = state.evidenceFor(net)
        return features(server, state.copy(okCount = e.successes, failCount = (e.checks - e.successes).coerceAtLeast(0),
            realMs = if (e.checks == 0) -1 else if (e.latency > 0) e.latency.toInt() else 0,
            tcpMs = if (e.checks == 0) -1 else state.tcpMs,
            bigOk = state.bigOk.takeIf { e.checks > 0 }, checkedAt = e.at, maskId = state.maskFor(net)))
    }

    @Volatile var seen = 0; private set
    private var dirty = false

    @Synchronized fun observationCount(net: String): ObservationCount {
        if (scoped(net)) return ObservationCount(contexts[contextKey(net)]?.checks?.toInt() ?: 0, true)
        return ObservationCount((trained[kind(net)] ?: 0.0).toInt(), false)
    }

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
    @Synchronized fun p(net: String, s: Server, st: ServerState): Double = probability(
        if (scoped(net)) contexts[contextKey(net)]?.weights ?: prior() else weights(net), localFeatures(net, s, st))

    fun learningRate(checks: Double): Double = maxOf(0.01, 0.2 / kotlin.math.sqrt(1.0 + checks.coerceAtLeast(0.0) / 50.0))
    @Synchronized fun learn(net: String, s: Server, st: ServerState, works: Boolean, weight: Double = 1.0) {
        if (!weight.isFinite() || weight <= 0) return
        val importance = weight.coerceAtMost(1.0)
        fun update(k: DoubleArray, x: DoubleArray, checks: Double) {
            val err = (if (works) 1.0 else 0.0) - probability(k, x)
            val lr = learningRate(checks) * importance
            for (i in 0 until N) k[i] = (k[i] + lr * err * x[i] - 0.0005 * importance * k[i]).coerceIn(-8.0, 8.0)
        }
        val family = kind(net); val checks = trained[family] ?: 0.0
        update(weights(net), features(s, st), checks)
        trained[family] = (checks + importance).coerceAtMost(10_000_000.0)
        if (scoped(net)) {
            val key = contextKey(net)
            val model = contexts.remove(key) ?: LocalModel(prior(), 0.0)
            update(model.weights, localFeatures(net, s, st), model.checks)
            model.checks = (model.checks + importance).coerceAtMost(10_000_000.0)
            contexts[key] = model
            while (contexts.size > MAX_CONTEXTS) contexts.remove(contexts.keys.first())
        }
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
        .put("cloudSig", cloudSig).put("contexts", JSONArray().apply {
            contexts.forEach { (key, model) -> put(JSONObject().put("key", key).put("n", model.checks).put("w", JSONArray(model.weights.toList()))) }
        }).apply { w.forEach { (k, v) -> put(k, JSONArray(v.toList())) } }.toString()

    /** Atomic model import: validate the whole payload before replacing any weights. */
    @Synchronized fun restore(text: String): Boolean = runCatching {
        if (text.length > 64_000) return@runCatching false
        val o = JSONObject(text); val models = HashMap<String, DoubleArray>(); val counts = HashMap<String, Double>()
        val total = o.optLong("seen", 0).coerceIn(0, 10_000_000).toInt()
        listOf("m", "w").forEach { k ->
            if (o.has(k)) models[k] = vector(o.optJSONArray(k)) ?: return@runCatching false
            // Older files had one global counter: preserve their conservative learning rate on migration.
            val n = o.optJSONObject("trained")?.optDouble(k, 0.0) ?: total.toDouble()
            if (!n.isFinite() || n < 0 || n > 10_000_000) return@runCatching false
            counts[k] = n
        }
        val local = LinkedHashMap<String, LocalModel>()
        o.optJSONArray("contexts")?.let { list ->
            if (list.length() > MAX_CONTEXTS) return@runCatching false
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: return@runCatching false
                val key = item.optString("key"); val n = item.optDouble("n", -1.0)
                if (!key.matches(Regex("[0-9a-f]{16}")) || key in local || !n.isFinite() || n !in 0.0..10_000_000.0) return@runCatching false
                val vector = vector(item.optJSONArray("w")) ?: return@runCatching false
                local[key] = LocalModel(vector, n)
            }
        }
        w.clear(); w.putAll(models); trained.clear(); trained.putAll(counts); contexts.clear(); contexts.putAll(local)
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
    @Synchronized fun clear() { w.clear(); trained.clear(); contexts.clear(); seen = 0; cloudSig = ""; dirty = false }
    @Synchronized fun reload(ctx: android.content.Context) { clear(); load(ctx) }
    @Synchronized fun report(): String = if (seen < 20) "Мини-нейросеть только учится (наблюдений: $seen)." else "Локальная модель: $seen наблюдений, отдельных профилей сетей: ${contexts.size}. Подсказывает порядок проверок; скорость решают реальные замеры."
}
