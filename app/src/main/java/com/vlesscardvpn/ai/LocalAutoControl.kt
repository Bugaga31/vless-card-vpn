package com.vlesscardvpn.ai

import android.content.Context
import android.net.*
import android.os.SystemClock
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.model.ServerState
import java.util.concurrent.atomic.AtomicLong

/** LLM chooses a bounded search policy, never a PASS result or an endpoint credential. */
object LocalAutoControl {
    private val revision = AtomicLong()
    private var initialized = false
    @Volatile private var watching = false
    @Volatile var enabled = false; private set
    private data class Cached(val revision: Long, val plan: LocalAiPolicy.Plan, val energy: LocalAiPolicy.Energy?, val at: Long)
    @Volatile private var cached: Cached? = null
    @Synchronized fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        enabled = ctx.getSharedPreferences("local_chat", 0).getBoolean("auto_control", false)
        watching = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { invalidate() }
                override fun onLost(network: Network) { invalidate() }
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { invalidate() }
            })
        }.isSuccess
    }
    fun invalidate() { revision.incrementAndGet(); cached = null }
    fun ticket(): Long? = if (watching) revision.get() else null
    fun current(ticket: Long?) = ticket != null && watching && ticket == revision.get()
    fun enable(ctx: Context, value: Boolean) {
        init(ctx); invalidate(); enabled = value
        ctx.getSharedPreferences("local_chat", 0).edit().putBoolean("auto_control", value).apply()
        Store.update { it.copy(settings = it.settings.copy(neuralRouting = value)) }
        com.vlesscardvpn.core.NeuralRouting.load(ctx)
        com.vlesscardvpn.core.NeuralRouting.controlChanged()
    }
    fun accept(ticket: Long?, plan: LocalAiPolicy.Plan?, energy: LocalAiPolicy.Energy?) {
        if (enabled && current(ticket) && (plan != null || energy != null))
            cached = Cached(ticket!!, plan ?: LocalAiPolicy.Plan.BALANCED, energy, SystemClock.elapsedRealtime())
    }
    fun activePlan(): LocalAiPolicy.Plan? = cached?.takeIf {
        enabled && current(it.revision) && SystemClock.elapsedRealtime() - it.at in 0..300000
    }?.plan
    fun effectivePowerProfile(userProfile: String): String {
        val c = cached ?: return userProfile
        return if (enabled && current(c.revision) && SystemClock.elapsedRealtime() - c.at in 0..300000 &&
            c.energy == LocalAiPolicy.Energy.ECO) "eco" else userProfile
    }
    /** Only current-network observations may change tie order inside the existing probability bucket. */
    fun tieScore(network: String, state: ServerState): Double {
        val plan = activePlan() ?: return 0.0
        val e = state.netEvidence[network + "|" + state.maskFor(network)] ?: return 1.0
        return LocalAiPolicy.rankHint(plan, e.checks, e.successes, e.streak, e.latency, e.ema,
            System.currentTimeMillis() - e.at in 0..300000)
    }
}
