package com.vlesscardvpn.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.ReceiveChannel

/** Work conserving, bounded foreground scheduler. Only native onAttempt records attempted IDs. */
object ForegroundDiscoveryPump {
    const val MAX_BATCHES = 6
    suspend fun <T> run(sources: ReceiveChannel<List<T>>, cached: () -> List<T>,
        keepGoing: () -> Boolean, usable: (T) -> Boolean, probe: suspend (List<T>) -> Unit): Int {
        var count = 0
        var dequeues = 0
        while (keepGoing() && count < MAX_BATCHES && dequeues < 16) {
            currentCoroutineContext().ensureActive()
            val ready = sources.tryReceive().getOrNull()
            val batch = ready ?: cached().filter(usable).takeIf { it.isNotEmpty() }
                ?: sources.receiveCatching().getOrNull() ?: break
            dequeues++
            val candidates = batch.filter(usable)
            if (candidates.isEmpty()) continue
            count++
            probe(candidates)
        }
        return count
    }
}
