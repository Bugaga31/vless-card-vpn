package com.vlesscardvpn.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Finite foreground sessions built from cancellable bounded waves. */
object AutoSearchCampaign {
    const val MAX_WAVES = 4
    const val MAX_TOTAL_MS = 45_000L
    enum class Stop { FOUND, WAVE_LIMIT, TIME_LIMIT }
    data class Outcome<T>(val value:T?,val stop:Stop,val waves:Int,val attempted:Int)
    private class Finished(val reason:Stop):RuntimeException(null,null,false,false)

    /** A connect-button session is finite, even offline or while the network keeps changing.
     * Cancellation belongs to the caller; only this scope's own timeout becomes TIME_LIMIT.
     * The legacy wave runner stays reusable, but foreground Actions uses this bounded entry.
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

    const val RETRY_CATALOG_MS = 60_000L
    fun backoff(round: Int): Long = 1000L * (round.coerceIn(0, 4) + 1)

    suspend fun <T : Any> run(
        network: () -> String,
        online: () -> Boolean,
        elapsed: () -> Long,
        waiting: (round: Int, offline: Boolean) -> Unit,
        exhausted: (Set<String>) -> Boolean = { false },
        pause: suspend (Long) -> Unit = { delay(it) },
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
                pause(2000L)
                continue
            }
            val result = probe(round, attempted)
            currentCoroutineContext().ensureActive()
            if (result != null && network() == identity) return result
            waiting(round, false)
            pause(backoff(round))
            round = if (round == Int.MAX_VALUE) 0 else round + 1
        }
    }
}
