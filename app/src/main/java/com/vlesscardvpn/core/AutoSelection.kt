package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import com.vlesscardvpn.model.ServerState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Bounded foreground comparison. Cloud/old measurements suggest candidates, never certify a winner. */
object AutoSelection {
    const val REFRESH_MS = 5 * 60000L
    const val TOTAL_MS = 7000L
    const val PROBE_MS = 2500L
    const val MIN_PROBE_MS = 800L

    fun needsSample(s: ServerState, net: String, now: Long): Boolean =
        ServerChoice.measuredSpeed(s, net, now) == 0 || now - s.speedCheckedAt >= REFRESH_MS

    /** Keep the measured leader, reserve a challenger slot, then prefer other hosts over link clones. */
    fun finalists(candidates: List<Server>, state: (Server) -> ServerState, net: String, now: Long): List<Server> {
        val ranked = candidates.distinctBy { it.id }.filter { state(it).works }
            .sortedWith(ServerChoice.downloadOrder(state,net,now))
        val leader = ranked.minWithOrNull(ServerChoice.downloadOrder(state, net, now)) ?: return emptyList()
        val others = ranked.filter { it.id != leader.id }
        val challengers = ServerChoice.diverse(others.filter { needsSample(state(it), net, now) }
            .sortedBy { ServerChoice.candidateScore(state(it),net,now) }, others.size)
        val challenger = challengers.firstOrNull { !it.address.equals(leader.address, ignoreCase = true) }
            ?: challengers.firstOrNull()
        return ServerChoice.diverse(listOfNotNull(leader, challenger) + ranked, 3)
    }

    /** One download at a time, including core startup/mutex waiting in the total deadline.
     * A timeout is inconclusive, not a negative training sample. Parent cancellation still propagates.
     */
    suspend fun compare(candidates: List<Server>, state: (Server) -> ServerState, net: String,
                        now: () -> Long, elapsed: () -> Long, validNetwork: () -> Boolean,
                        allowDownload: () -> Boolean, gaming: Boolean,
                        onInconclusive: (Server, Long) -> Unit = { _, _ -> },
                        probe: suspend (Server, Long) -> Unit): Server? {
        val valid = if (gaming) candidates.distinctBy { it.id }.filter { state(it).works }
            .sortedBy { ServerChoice.latencyScore(state(it).evidenceFor(net), state(it).realMs) }.take(3)
        else finalists(candidates, state, net, now())
        val started = elapsed()
        val attemptedAt = HashMap<String, Long>()
        if (!gaming) for (server in valid) {
            currentCoroutineContext().ensureActive()
            if (!validNetwork()) return null
            if (!allowDownload()) break
            if (!needsSample(state(server), net, now())) continue
            val remaining = TOTAL_MS - (elapsed() - started).coerceAtLeast(0)
            if (remaining < MIN_PROBE_MS) break
            val budget = minOf(PROBE_MS, remaining)
            attemptedAt[server.id] = state(server).speedCheckedAt
            withTimeoutOrNull(budget) { probe(server, budget) }
        }
        currentCoroutineContext().ensureActive()
        if (!validNetwork()) return null
        // Persist provenance invalidation too: later ranking/launches must not resurrect an old speed.
        attemptedAt.forEach { (id, at) ->
            val server = valid.first { it.id == id }
            if (state(server).speedCheckedAt <= at) onInconclusive(server, at)
        }
        val results = valid.associate { it.id to state(it) }
        fun effective(server: Server): ServerState {
            val result = results.getValue(server.id)
            return if (attemptedAt[server.id]?.let { at -> result.speedCheckedAt <= at } == true)
                result.copy(speedCheckedAt = 0) else result
        }
        val working = valid.filter { results.getValue(it.id).works }
        return if (gaming) working.minByOrNull {
            ServerChoice.latencyScore(results.getValue(it.id).evidenceFor(net), results.getValue(it.id).realMs)
        } else working.minWithOrNull(ServerChoice.downloadOrder(::effective, net, now()))
    }
}
