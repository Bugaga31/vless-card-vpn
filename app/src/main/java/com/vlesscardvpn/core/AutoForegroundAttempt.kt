package com.vlesscardvpn.core

import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

/** One button press, one monotonic deadline, including mutex/worker waits and actual-core handoff.
 * RAM only. Context values never appear in logs or diagnostics. Cleanup may outlive cancellation of JNI.
 */
class AutoForegroundAttempt(val generation: Long, private val network: String, private val handle: String?,
                            private val clock: () -> Long, budgetMs: Long = TARGET_MS) {
    companion object {
        const val TARGET_MS = 15_000L
        const val FINAL_RESERVE_MS = 5_000L
        const val MAX_CANDIDATES = 8
        private val sequence = AtomicLong()
        fun begin(network: String, handle: String?, clock: () -> Long) =
            AutoForegroundAttempt(sequence.incrementAndGet(), network, handle, clock)
    }
    private val deadline = clock() + budgetMs.coerceIn(1, TARGET_MS)
    @Volatile private var closedReason: String? = null
    fun remainingMs(): Long = if (closedReason != null) 0 else (deadline - clock()).coerceAtLeast(0)
    fun searchMs(): Long = (remainingMs() - FINAL_RESERVE_MS).coerceAtLeast(0)
    fun valid(network: String, handle: String?) = closedReason == null && this.network == network && this.handle == handle && remainingMs() > 0
    fun close(reason: String) { closedReason = reason }
    fun reason(): String = closedReason ?: if (remainingMs() == 0L) "budget-exhausted" else "searching"
    fun ensureOpen() {
        if (closedReason == "connected") return // final-chain success may beat the search coroutine's bookkeeping
        if (closedReason != null) throw kotlinx.coroutines.CancellationException(closedReason)
        if (remainingMs() <= 0) throw AutoBudgetExpired()
    }
    suspend fun <T> within(block: suspend () -> T): T {
        val left = remainingMs()
        if (left <= 0) throw AutoBudgetExpired()
        return withTimeout(left) { block().also { ensureOpen() } }
    }
}
class AutoBudgetExpired : IllegalStateException("Бюджет Auto исчерпан; пригодный маршрут не подтверждён")

/** Fixed vocabulary only: never log endpoints, UUIDs, subscriptions, masks or media URLs. */
object AutoDiagnostics {
    enum class Verdict { PASS, FAIL, UNKNOWN, STALE, CANCELLED }
    data class Event(val generation: Long, val kind: String, val verdict: Verdict, val reason: String)
    val events = kotlinx.coroutines.flow.MutableStateFlow<List<Event>>(emptyList())
    private val reasons = setOf("tunnel-failed", "telegram-failed", "telegram-unknown", "youtube-failed",
        "youtube-discovery-unavailable", "endpoint-unavailable", "budget-exhausted", "cancelled",
        "stale-network-result", "no-eligible-candidates", "data-passed", "services-passed")
    @Synchronized fun record(generation: Long, kind: String, verdict: Verdict, reason: String) {
        require(reason in reasons)
        require(kind in setOf("search", "tunnel", "telegram", "youtube"))
        events.value = (events.value + Event(generation,kind,verdict,reason)).takeLast(64)
    }
    fun probe(generation: Long, p: Probe, servicesChecked: Boolean = true) {
        record(generation,"tunnel",if(p.httpsHosts>=2) Verdict.PASS else Verdict.UNKNOWN,
            if(p.httpsHosts>=2) "data-passed" else "endpoint-unavailable")
        if (!servicesChecked) return
        record(generation,"telegram",when(p.tgOk) {true->Verdict.PASS;false->Verdict.FAIL;null->Verdict.UNKNOWN},
            when(p.tgOk) {true->"services-passed";false->"telegram-failed";null->"telegram-unknown"})
        record(generation,"youtube",when(p.ytOk) {true->Verdict.PASS;false->Verdict.FAIL;null->Verdict.UNKNOWN},
            when(p.ytOk) {true->"services-passed";false->"youtube-failed";null->"youtube-discovery-unavailable"})
    }
}
