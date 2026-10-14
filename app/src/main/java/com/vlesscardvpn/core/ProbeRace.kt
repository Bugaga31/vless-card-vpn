package com.vlesscardvpn.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** Cancel siblings after enough successes; cancelled probes are not negative training examples. */
object ProbeRace {
    suspend fun run(indices: List<Int>, parallel: Int, stopAfter: Int,
                    skip: (Int) -> Boolean = { false }, staggerMs: Long = 0, probe: suspend (Int) -> Probe,
                    onEach: (Int, Probe) -> Unit) = coroutineScope {
        val parent = currentCoroutineContext()
        val sem = Semaphore(parallel.coerceAtLeast(1))
        val successes = AtomicInteger()
        val jobs = mutableListOf<Job>()
        indices.forEachIndexed { position, i ->
            jobs += launch(start = CoroutineStart.LAZY) {
                try {
                    if (staggerMs > 0) delay((position / 2) * staggerMs.coerceIn(0, 500))
                    sem.withPermit {
                        if (successes.get() >= stopAfter.coerceAtLeast(1) || skip(i)) onEach(i, Tester.SKIPPED)
                        else {
                            val result = probe(i)
                            val reached = result.works && successes.incrementAndGet() >= stopAfter.coerceAtLeast(1)
                            onEach(i, result)
                            if (reached) {
                                val self = currentCoroutineContext()[Job]
                                jobs.forEach { if (it !== self) it.cancel() }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    if (!parent.isActive) throw e
                    onEach(i, Tester.SKIPPED)
                }
            }
        }
        jobs.forEach { it.start() }
        jobs.joinAll()
    }
}
