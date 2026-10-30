package com.vlesscardvpn.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Cancellable campaigns; per-wave limits do not limit an explicit persistent search. */
object AutoSearchCampaign {
    const val MAX_WAVES = 4
    const val MAX_TOTAL_MS = 45_000L
    enum class Stop { FOUND, WAVE_LIMIT, TIME_LIMIT }
    enum class WaitReason { OFFLINE, CATALOG_RETRY, BACKOFF }
    data class WaitState(val round:Int,val reason:WaitReason,val delayMs:Long)
    data class Outcome<T>(val value:T?,val stop:Stop,val waves:Int,val attempted:Int)
    private class Finished(val reason:Stop):RuntimeException(null,null,false,false)

    /** A connect-button session is finite, even offline or while the network keeps changing.
     * Cancellation belongs to the caller; only this scope's own timeout becomes TIME_LIMIT.
     * Retained for bounded diagnostics; the connect button uses runPersistent.
     */
    suspend fun <T:Any> runBounded(
        network:()->String,online:()->Boolean,elapsed:()->Long,
        waiting:(Int,Boolean)->Unit,exhausted:(Set<String>)->Boolean={false},
        pause:suspend (Long)->Unit={delay(it)},
        maxDurationMs:Long=MAX_TOTAL_MS,
        probe:suspend (Int,MutableSet<String>)->T?,
    ):Outcome<T> {
        require(maxDurationMs in 1..MAX_TOTAL_MS)
        val start=elapsed();var waves=0;var attempted=0;var offlineWait=false
        fun checkClock() {
            val now=elapsed()
            if(now<start || now-start>=maxDurationMs) throw Finished(Stop.TIME_LIMIT)
        }
        return try {
            val found=kotlinx.coroutines.withTimeoutOrNull(maxDurationMs) {
                run(network,online,elapsed,waiting={round,offline->
                    checkClock();offlineWait=offline
                    if(!offline && waves>=MAX_WAVES) throw Finished(Stop.WAVE_LIMIT)
                    waiting(round,offline)
                },exhausted=exhausted,pause={ms->pause(if(offlineWait) ms else minOf(ms,200L))}) {round,ids->
                    checkClock()
                    if(waves>=MAX_WAVES) throw Finished(Stop.WAVE_LIMIT)
                    waves++
                    try {probe(round,ids)} finally {attempted=ids.size}
                }
            }
            Outcome(found,if(found!=null) Stop.FOUND else Stop.TIME_LIMIT,waves,attempted)
        } catch(e:Finished) {Outcome(null,e.reason,waves,attempted)}
    }

    /** No wall-clock or wave limit. The caller owns cancellation and every probe stays bounded. */
    suspend fun <T:Any> runPersistent(
        network:()->String, online:()->Boolean, elapsed:()->Long,
        waiting:(Int,Boolean)->Unit, exhausted:(Set<String>)->Boolean={false},
        restMs:()->Long={0L}, pause:suspend(Long)->Unit={delay(it)},
        onWait:(WaitState)->Unit={},
        probe:suspend(Int,MutableSet<String>)->T?,
    ):Outcome<T> {
        var waves=0; var attempted=0
        val value=run(network,online,elapsed,waiting,exhausted,pause,
            idleOnExhaustion=true,restMs=restMs,onWait=onWait) { round,ids ->
            if(waves<Int.MAX_VALUE) waves++
            try {probe(round,ids)} finally {attempted=ids.size}
        }
        return Outcome(value,Stop.FOUND,waves,attempted)
    }

    const val RETRY_CATALOG_MS = 60_000L
    const val WAVE_PAUSE_MS = 1000L
    @Suppress("UNUSED_PARAMETER")
    fun backoff(round: Int): Long = WAVE_PAUSE_MS

    suspend fun <T : Any> run(
        network: () -> String,
        online: () -> Boolean,
        elapsed: () -> Long,
        waiting: (round: Int, offline: Boolean) -> Unit,
        exhausted: (Set<String>) -> Boolean = { false },
        pause: suspend (Long) -> Unit = { delay(it) },
        idleOnExhaustion: Boolean = false,
        restMs: () -> Long = {0L},
        onWait: (WaitState) -> Unit = {},
        probe: suspend (round: Int, attempted: MutableSet<String>) -> T?,
    ): T {
        var identity = network()
        var resetAt = elapsed()
        var round = 0
        val attempted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = network()
            val now = elapsed()
            if (next != identity || now < resetAt || (now - resetAt >= RETRY_CATALOG_MS && exhausted(attempted))) {
                attempted.clear()
                resetAt = now
                if (next != identity) round = 0
                identity = next
            }
            if (!online()) {
                waiting(round, true)
                onWait(WaitState(round,WaitReason.OFFLINE,2000L))
                pause(2000L)
                continue
            }
            // Retest exhausted catalogs at most once per minute. Re-evaluate every second so
            // new subscriptions and network changes can resume discovery without a busy loop.
            if (idleOnExhaustion && attempted.isNotEmpty() && exhausted(attempted)) {
                waiting(round, false)
                val wait=maxOf(1000L, restMs().coerceIn(0L, 60000L))
                onWait(WaitState(round,WaitReason.CATALOG_RETRY,wait))
                pause(wait)
                continue
            }
            val result = probe(round, attempted)
            currentCoroutineContext().ensureActive()
            if (result != null && network() == identity) return result
            waiting(round, false)
            val wait=maxOf(backoff(round), restMs().coerceIn(0L, 60000L))
            onWait(WaitState(round,WaitReason.BACKOFF,wait))
            pause(wait)
            round = if (round == Int.MAX_VALUE) 0 else round + 1
        }
    }
}
