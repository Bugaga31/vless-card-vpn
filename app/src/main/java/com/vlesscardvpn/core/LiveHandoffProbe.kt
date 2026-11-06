package com.vlesscardvpn.core

import com.vlesscardvpn.model.ServerState

/**
 * Wi-Fi⇄cellular: the running single-server core re-dials over the new network (the app itself is outside its VPN),
 * so one bounded body probe through its own SOCKS — the same Auto gates as the live check — is fresh evidence for
 * *this* network. It races the isolated handoff wave instead of waiting behind it; old-network samples are never reused.
 */
object LiveHandoffProbe {
    const val BUDGET_MS = 6500L

    /** Only a single plain route can be attributed: no chain, no Tor, no WARP hop, no mux session pinned to the old link. */
    fun eligible(automatic: Boolean, servers: Int, chain: Boolean, tor: Boolean, warpChained: Boolean, mux: Boolean): Boolean =
        automatic && servers == 1 && !chain && !tor && !warpChained && !mux

    /** The running mask may become this network's mask only when the network has no own learned choice yet. */
    fun maskAllowed(state: ServerState, net: String, mask: String): Boolean =
        state.maskFor(net) == mask || state.netMasks[net].isNullOrEmpty()

    fun accepted(p: Probe, profile: AutoCheckProfile, maxMs: Int): Boolean = profile.accepts(p, maxMs)

    /** Publishes the sample for [net]+[mask]; a newer sample or another learned mask keeps the state unchanged. */
    fun commit(old: ServerState, p: Probe, profile: AutoCheckProfile, net: String, mask: String, started: Long, now: Long): ServerState {
        if (old.checkedAt > started || !maskAllowed(old, net, mask) || !profile.accepts(p, AutoLatencyPolicy.MAX_MS)) return old
        val pinned = if (old.maskFor(net) == mask) old else old.copy(netMasks = old.netMasks + (net to mask))
        val committed = LiveRouteEvidence.commit(pinned, p, profile, net, mask, started, now)
        if (committed === pinned) return old
        val evidence = (committed.netEvidence + ((net + "|" + mask) to committed.evidenceFor(net).observe(true, p.realMs, now)))
            .entries.sortedByDescending { it.value.at }.take(8).associate { it.toPair() }
        return committed.copy(netEvidence = evidence, bigOk = committed.bigOk.takeIf { it == true }, okCount = committed.okCount + 1)
    }
}
