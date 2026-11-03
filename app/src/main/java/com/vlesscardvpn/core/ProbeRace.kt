package com.vlesscardvpn.core

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Fixed-size workers: failed slots take the next candidate immediately; a winner cancels siblings. */
object ProbeRace {
    suspend fun run(indices: List<Int>, parallel: Int, stopAfter: Int,
                    skip: (Int) -> Boolean = { false }, staggerMs: Long = 0, probe: suspend (Int) -> Probe,
                    accepted: (Probe) -> Boolean = { it.works }, onEach: (Int, Probe) -> Unit) = coroutineScope {
        val parent = currentCoroutineContext()
        val next = AtomicInteger(); val successes = AtomicInteger()
        val jobs = mutableListOf<Job>()
        repeat(minOf(parallel.coerceAtLeast(1), indices.size)) { worker ->
            jobs += launch(start = CoroutineStart.LAZY) {
                // Hedge only worker startup, not every candidate. A failed slot is work-conserving.
                if (staggerMs > 0) delay((worker / 2) * staggerMs.coerceIn(0,500))
                while (isActive && successes.get() < stopAfter.coerceAtLeast(1)) {
                    val position = next.getAndIncrement()
                    if (position >= indices.size) break
                    val i = indices[position]
                    var reported = false
                    try {
                        if (successes.get() >= stopAfter.coerceAtLeast(1) || skip(i)) {
                            reported = true; onEach(i,Tester.SKIPPED)
                        } else {
                            val result = probe(i)
                            val reached = accepted(result) && successes.incrementAndGet() >= stopAfter.coerceAtLeast(1)
                            reported = true; onEach(i,result)
                            if (reached) {
                                val self = currentCoroutineContext()[Job]
                                jobs.forEach { if(it !== self) it.cancel() }
                            }
                        }
                    } catch(e:CancellationException) {
                        if(!parent.isActive) throw e
                        if(!reported) onEach(i,Tester.SKIPPED)
                        return@launch
                    }
                }
            }
        }
        jobs.forEach {it.start()};jobs.joinAll()
        // No coroutine is created for unadmitted catalogue items; preserve the skipped callback contract.
        for(position in next.get().coerceAtMost(indices.size) until indices.size) onEach(indices[position],Tester.SKIPPED)
    }
}
