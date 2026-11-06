package com.vlesscardvpn.tor

/**
 * Bridges after the start: Tor's bootstrap phase never goes back from 100 %, so a lost route (network handoff, a
 * bridge blocked mid-session, a restarted VLESS upstream, the phone waking from a long sleep) is watched separately.
 * Escalation is bounded and never leaves the Tor route: first the daemon's own connections are kicked, then other
 * bridges are taken and Tor restarts. Pure and clock-injected; one instance per Tor session.
 */
class TorBridgeHealth(
    private val lostMs: Long = LOST_MS,
    private val kickWaitMs: Long = KICK_WAIT_MS,
    private val windowMs: Long = WINDOW_MS,
    private val maxRestarts: Int = MAX_RESTARTS,
) {
    enum class Action { NONE, KICK, ROTATE }

    private var lostSince = UNSET
    private var kickedAt = UNSET
    private val restarts = ArrayDeque<Long>()

    /**
     * [healthy]: Tor's own view (see [state]); null = the daemon did not answer, nothing is judged.
     * [online]: a physical network exists — without one nothing is a bridge failure.
     * ROTATE only proposes a restart: the caller confirms the loss and spends the budget with [allowRestart].
     */
    fun observe(healthy: Boolean?, online: Boolean, now: Long): Action {
        if (healthy == true || !online) { recovered(); return Action.NONE }
        if (healthy == null) return Action.NONE
        if (lostSince == UNSET || now < lostSince) { lostSince = now; kickedAt = UNSET; return Action.NONE }
        if (now - lostSince < lostMs) return Action.NONE
        if (kickedAt == UNSET || now < kickedAt) { kickedAt = now; return Action.KICK }
        if (now - kickedAt < kickWaitMs) return Action.NONE
        // The kick did not help: the next loss starts a fresh cycle (grace → kick → restart) whatever the caller does.
        recovered()
        return Action.ROTATE
    }

    /** Whether a restart would fit the budget now; spends nothing. */
    fun canRestart(now: Long): Boolean { prune(now); return restarts.size < maxRestarts }

    /** One budget for every restart of this session (a stalled start too): at most [maxRestarts] per [windowMs]. */
    fun allowRestart(now: Long): Boolean {
        if (!canRestart(now)) return false
        restarts.addLast(now)
        return true
    }

    private fun prune(now: Long) {
        while (restarts.isNotEmpty() && (now - restarts.first() >= windowMs || now < restarts.first())) restarts.removeFirst()
    }

    /** The route works again (a probe passed, a start reached DONE): the next loss gets the full grace period. */
    fun recovered() { lostSince = UNSET; kickedAt = UNSET }

    companion object {
        private const val UNSET = Long.MIN_VALUE
        /** Control-port poll after DONE: one loopback GETINFO pair. */
        const val POLL_MS = 10_000L
        const val IDLE_POLL_MS = 30_000L
        /** Tor rebuilds a dropped circuit by itself within seconds; only a longer loss is acted on. */
        const val LOST_MS = 30_000L
        const val KICK_WAIT_MS = 45_000L
        const val WINDOW_MS = 30 * 60_000L
        const val MAX_RESTARTS = 3

        /**
         * Tor's flags: `status/circuit-established` drops to 0 after a clock jump (a long sleep) or a disabled network;
         * `network-liveness` turns «down» when circuits time out with no cell received. Either is a suspected loss,
         * confirmed by one real request before anything is restarted. null = the daemon did not say.
         */
        fun state(circuit: Boolean?, live: Boolean?): Boolean? = when {
            circuit == false || live == false -> false
            circuit == true -> true
            else -> null
        }
    }
}
