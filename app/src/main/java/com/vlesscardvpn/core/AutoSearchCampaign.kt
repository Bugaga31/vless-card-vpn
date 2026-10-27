package com.vlesscardvpn.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Continuous user-requested search made of bounded waves; no tight loop or unbounded concurrency. */
object AutoSearchCampaign {
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
        val attempted = mutableSetOf<String>()
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
