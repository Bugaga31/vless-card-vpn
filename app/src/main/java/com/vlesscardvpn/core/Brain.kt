package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState

/**
 * «Мини-нейросеть» помощника: a tiny model (one neuron, 12 weights, < 1 KB, microseconds per call) that learns on the
 * phone from every server check which servers are likely to work in this kind of network — mobile and Wi-Fi learn
 * separately. Used to choose which spares to try first, so a rescue hits a working server sooner. No downloads,
 * no background work of its own, nothing leaves the phone.
 */
object Brain {
    const val N = 12
    private val w = HashMap<String, DoubleArray>()
    @Volatile var seen = 0; private set
    private var dirty = false

    fun features(s: Server, st: ServerState): DoubleArray {
        val tries = (st.okCount + st.failCount).toDouble()
        return doubleArrayOf(
            1.0,
            if (st.tcpMs > 0) minOf(st.tcpMs, 2000) / 1000.0 else 2.0,
            if (tries > 0) st.okCount / tries else 0.5,
            minOf(tries, 20.0) / 20.0,
            if (st.realMs > 0) 1.0 else if (st.realMs == 0) -1.0 else 0.0,
            when (st.bigOk) { true -> 1.0; false -> -1.0; null -> 0.0 },
            if (s.security == "reality") 1.0 else 0.0,
            if (Revive.cdnLike(s)) 1.0 else 0.0,
            if (s.protocol == "wireguard" || s.protocol == "hysteria2") 1.0 else 0.0,
            if (st.maskId.isNotEmpty()) 1.0 else 0.0,
            if (s.port == 443) 1.0 else 0.0,
            ((System.currentTimeMillis() - st.checkedAt).coerceAtLeast(0) / 86_400_000.0).coerceAtMost(3.0) / 3.0,
        )
    }

    private fun kind(net: String) = if (net.startsWith("Моб.")) "m" else "w"
    private fun weights(net: String) = w.getOrPut(kind(net)) { DoubleArray(N).also { it[4] = 1.5; it[2] = 1.0; it[1] = -0.5; it[5] = 0.5 } }

    /** Chance (0..1) that the server works now in this network. */
    @Synchronized fun p(net: String, s: Server, st: ServerState): Double {
        val x = features(s, st); val k = weights(net)
        var z = 0.0; for (i in 0 until N) z += k[i] * x[i]
        return 1 / (1 + kotlin.math.exp(-z.coerceIn(-30.0, 30.0)))
    }

    /** One step of online learning: the check before ([st]) and what came out ([works]). */
    @Synchronized fun learn(net: String, s: Server, st: ServerState, works: Boolean) {
        val x = features(s, st); val k = weights(net)
        val err = (if (works) 1.0 else 0.0) - p(net, s, st)
        val lr = 0.05
        for (i in 0 until N) k[i] = (k[i] + lr * err * x[i] - 0.0005 * k[i]).coerceIn(-8.0, 8.0)
        seen++; dirty = true
    }

    /** Working candidates first by the model, then by speed. */
    fun order(net: String, list: List<Server>, state: (Server) -> ServerState): List<Server> =
        list.sortedWith(compareByDescending<Server> { (p(net, it, state(it)) * 10).toInt() }.thenBy { state(it).score }.thenBy { state(it).tcpMs.let { t -> if (t > 0) t else Int.MAX_VALUE } })

    @Synchronized fun save(ctx: android.content.Context) {
        if (!dirty) return; dirty = false
        val o = org.json.JSONObject().put("seen", seen)
        w.forEach { (k, v) -> o.put(k, org.json.JSONArray(v.toList())) }
        ctx.getSharedPreferences("brain", 0).edit().putString("w", o.toString()).apply()
    }

    @Synchronized fun load(ctx: android.content.Context) = runCatching {
        if (w.isNotEmpty()) return@runCatching
        val o = org.json.JSONObject(ctx.getSharedPreferences("brain", 0).getString("w", null) ?: return@runCatching)
        seen = o.optInt("seen")
        listOf("m", "w").forEach { k -> o.optJSONArray(k)?.takeIf { it.length() == N }?.let { a -> w[k] = DoubleArray(N) { a.getDouble(it) } } }
    }

    fun report(): String = if (seen < 20) "Мини-нейросеть только учится (проверок: $seen)." else "Мини-нейросеть обучена на $seen проверках серверов — подсказывает, какие запасные пробовать первыми."
}
