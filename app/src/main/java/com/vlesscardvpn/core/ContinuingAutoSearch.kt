package com.vlesscardvpn.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One finite catalogue snapshot, bounded batches and one caller-owned deadline.
 * No subscription fetch, strategy mutation, repeated failed chain or deadline reset.
 * A probe receipt is not success: connect must verify the actual running chain.
 */
object ContinuingAutoSearch {
    enum class Outcome { CONNECTED, EXHAUSTED, DEADLINE, STALE }
    suspend fun <T> search(catalogue: List<T>, key: (T) -> String,
                          remainingMs: () -> Long, valid: () -> Boolean,
                          nextBatch: (List<T>) -> List<T> = { it.take(AutoForegroundAttempt.MAX_CANDIDATES) },
                          probeBatch: suspend (List<T>) -> Unit, confirmed: (T) -> Boolean = { true },
                          select: (List<T>) -> List<T>, fallbackReady: () -> Boolean = { false },
                          connect: suspend (List<T>) -> Boolean): Outcome {
        val pending = catalogue.distinctBy(key).toMutableList()
        val observed = mutableListOf<T>()
        val rejected = mutableSetOf<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!valid()) return Outcome.STALE
            if (remainingMs() <= 0) return Outcome.DEADLINE
            val available = observed.filter { key(it) !in rejected }
            val pool = select(available).distinctBy(key).filter { selected -> available.any { key(it) == key(selected) } }
            if (pool.isNotEmpty() && (pool.any(confirmed) || fallbackReady() || pending.isEmpty() || remainingMs() <= AutoForegroundAttempt.FINAL_RESERVE_MS)) {
                val connected = connect(pool)
                currentCoroutineContext().ensureActive()
                if (!valid()) return Outcome.STALE
                if (connected) return Outcome.CONNECTED
                rejected.addAll(pool.map(key))
                continue // try remaining data-proven routes before starting more network work
            }
            if (pending.isEmpty()) return Outcome.EXHAUSTED
            if (remainingMs() <= AutoForegroundAttempt.FINAL_RESERVE_MS) return Outcome.DEADLINE
            val batch = nextBatch(pending.toList()).distinctBy(key)
                .filter { proposed -> pending.any { key(it) == key(proposed) } }
                .take(AutoForegroundAttempt.MAX_CANDIDATES)
            if (batch.isEmpty()) return Outcome.EXHAUSTED
            val ids = batch.map(key).toSet()
            pending.removeAll { key(it) in ids }
            observed.addAll(batch)
            probeBatch(batch)
        }
    }
}
