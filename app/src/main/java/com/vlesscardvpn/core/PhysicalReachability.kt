package com.vlesscardvpn.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded workers: a stuck DNS lookup must never accumulate threads or starve fixed IP witnesses. */
internal class PhysicalReachability(
    private val fixed: Set<Pair<String, Int>>,
    private val probe: (String, Int, Int) -> Boolean
) {
    private fun workers(count: Int, name: String) = ThreadPoolExecutor(
        0, count, 30L, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, name).apply { isDaemon = true } }
    )
    private val fixedWorkers = workers(RouteLiveness.PHYSICAL_TARGETS.size, "physical-fixed")
    private val endpointWorkers = workers(RouteLiveness.MAX_SERVER_TARGETS, "physical-endpoint")

    fun check(targets: List<Pair<String, Int>>, timeoutMs: Int, waitMs: Long): Boolean {
        val unique = targets.distinct()
        if (unique.isEmpty()) return false
        val done = CountDownLatch(1)
        val reached = AtomicBoolean(false)
        val left = AtomicInteger(unique.size)
        for ((host, port) in unique) {
            val pool = if ((host to port) in fixed) fixedWorkers else endpointWorkers
            try {
                pool.execute {
                    try {
                        if (probe(host, port, timeoutMs)) { reached.set(true); done.countDown() }
                    } catch (_: Exception) {
                        // An individual witness is inconclusive, never a successful connection.
                    } finally {
                        if (left.decrementAndGet() == 0) done.countDown()
                    }
                }
            } catch (_: RejectedExecutionException) {
                // A previous lookup is still stuck. Do not queue more work behind it.
                if (left.decrementAndGet() == 0) done.countDown()
            }
        }
        try { done.await(waitMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        return reached.get()
    }
}
