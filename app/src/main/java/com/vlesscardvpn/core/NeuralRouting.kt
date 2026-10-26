package com.vlesscardvpn.core

import android.content.Context
import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import com.vlesscardvpn.xray.Mask
import com.vlesscardvpn.xray.Masks
import org.json.JSONObject

/** Opt-in local experimental controller. Only orders EXISTING candidates; it cannot edit a route,
 * lower an eligibility gate, add probes, extend deadlines, or interpret web content as instructions.
 */
object NeuralRouting {
    private val models = LinkedHashMap<String, RouteNeuralModel>()
    private var loaded = false
    private val revision = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var watching = false
    private var callback: android.net.ConnectivityManager.NetworkCallback? = null
    const val MAX_NETWORKS = 12
    data class Ticket internal constructor(internal val key: String, internal val generation: String?, internal val network: String,
                                          internal val features: DoubleArray, internal val revision: Long)
    private fun key(network: String) = Net.fingerprint("neural-routing-v1", network)
    @Synchronized fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        watching = runCatching {
            val cm = ctx.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
                ?: error("Network observer unavailable")
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { revision.incrementAndGet() }
                override fun onLost(network: android.net.Network) { revision.incrementAndGet() }
                override fun onLinkPropertiesChanged(network: android.net.Network, properties: android.net.LinkProperties) { revision.incrementAndGet() }
            }
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            cm.registerNetworkCallback(request, cb); callback = cb
        }.isSuccess
        runCatching {
            val raw = ctx.getSharedPreferences("neural_routing", 0).getString("models", null) ?: return@runCatching
            require(raw.length <= 100_000)
            val obj = JSONObject(raw); require(obj.length() <= MAX_NETWORKS)
            val next = LinkedHashMap<String, RouteNeuralModel>()
            obj.keys().forEach { k ->
                require(k.matches(Regex("[0-9a-f]{16}")))
                val model = RouteNeuralModel(); require(model.restore(obj.getString(k))); next[k] = model
            }
            models.putAll(next)
        }
    }
    @Synchronized fun save(ctx: Context) {
        if (!loaded) return
        ctx.getSharedPreferences("neural_routing", 0).edit().putString("models", JSONObject().apply {
            models.forEach { (k, v) -> put(k, v.encode()) }
        }.toString()).apply()
    }
    @Synchronized fun reset(ctx: Context) {
        models.clear(); revision.incrementAndGet(); loaded = watching; ctx.getSharedPreferences("neural_routing", 0).edit().clear().apply()
    }
    fun controlChanged() { revision.incrementAndGet() }
    /** Only current-network history is used. No global previous-network speed/service evidence. */
    fun features(network: String, server: Server, state: ServerState, mask: Mask?): DoubleArray {
        val e = state.netEvidence[network + "|" + (mask?.id ?: state.maskFor(network))] ?: com.vlesscardvpn.model.RouteEvidence()
        return doubleArrayOf(
            if (e.checks > 0) (2.0 * e.successes / e.checks - 1.0).coerceIn(-1.0, 1.0) else 0.0,
            (e.checks.coerceIn(0, 40) / 40.0),
            if (e.latency > 0 && e.checks > 0) (e.latency / 3000.0).coerceIn(0.0, 1.0) else 1.0,
            if (server.port == 443) 1.0 else 0.0,
            if (server.security == "reality") 1.0 else 0.0,
            if (server.protocol in listOf("wireguard", "hysteria2")) 1.0 else 0.0,
            if (mask?.packets?.isNotEmpty() == true) 1.0 else 0.0,
            if (mask?.viaByeDpi == true) 1.0 else 0.0,
            if (mask?.hasNoise == true) 1.0 else 0.0,
            if (mask?.mux == true) 1.0 else 0.0,
            if (mask != null) (Masks.cost(mask) / 10.0).coerceIn(0.0, 1.0) else 0.0,
            if (network.startsWith("Моб.")) 1.0 else 0.0,
        )
    }
    fun ticket(network: String, generation: String?, server: Server, state: ServerState, mask: Mask?): Ticket? =
        if (!Store.state.value.settings.neuralRouting || !watching || generation == null) null else
            Ticket(key(network), generation, network, features(network, server, state, mask), revision.get())
    internal fun current(ticket: Ticket, network: String, generation: String?, revision: Long) =
        ticket.network == network && ticket.generation == generation && generation != null && ticket.revision == revision
    /** UNKNOWN, skipped, no body proof, handoff and cancelled jobs never become labels. */
    fun label(p: Probe): Boolean? = when {
        p.realMs <= 0 || p.bodyBytes < TransferProof.REQUIRED_BYTES || p.bigOk != true -> null
        p.tgOk == false || p.ytOk == false -> false
        p.tgOk == true && p.ytOk == true -> true
        else -> null
    }
    @Synchronized fun observe(ticket: Ticket?, probe: Probe, network: String, generation: String?) {
        if (ticket == null || !Store.state.value.settings.neuralRouting || !current(ticket, network, generation, revision.get())) return
        val success = label(probe) ?: return
        val model = models.remove(ticket.key) ?: RouteNeuralModel()
        model.train(ticket.features, success); models[ticket.key] = model
        while (models.size > MAX_NETWORKS) models.remove(models.keys.first())
    }
    /** At most ±0.05 probability: the existing deterministic rank remains the primary prior. */
    @Synchronized fun adjustment(network: String, server: Server, state: ServerState, mask: Mask?): Double {
        if (!Store.state.value.settings.neuralRouting || !watching) return 0.0
        val model = models[key(network)]?.takeIf { it.ready() } ?: return 0.0
        return boundedAdjustment(model, features(network, server, state, mask))
    }
    internal fun boundedAdjustment(model: RouteNeuralModel, features: DoubleArray): Double =
        if (model.ready()) (model.predict(features) - .5) * .1 else 0.0
    @Synchronized fun report(network: String): String {
        if (!Store.state.value.settings.neuralRouting) return "Нейроуправление выключено"
        if (!watching) return "Нейроуправление на паузе: наблюдение за сменой сети недоступно"
        val m = models[key(network)]
        return if (m?.ready() == true) "Эксперимент: ${m.samples} примеров · ограниченное ранжирование маршрутов и масок"
        else "Сбор: ${m?.samples ?: 0}/40 · PASS ${m?.positives ?: 0}/8 · FAIL ${m?.negatives ?: 0}/8. Пока решает обычный Auto"
    }
}
