package com.vlesscardvpn.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded app-priority executors. Explicit checks are no longer deprioritized for other applications.
 * ProbeConcurrencyPolicy bounds admitted routes; CPU work remains off the UI thread.
 */
object Bg {
    private val n = AtomicInteger()
    private fun factory(name: String) = ThreadFactory { r ->
        Thread({
            // Normal app priority; plain JVM (unit tests) has no android.os.Process
            runCatching { android.os.Process.setThreadPriority(0) }
            r.run()
        }, "vc-$name-${n.incrementAndGet()}").apply { isDaemon = true }
    }
    /** Blocking network probes (sockets, HTTP through local proxies). */
    val io: CoroutineDispatcher = Executors.newFixedThreadPool(96, factory("io")).asCoroutineDispatcher()
    /** CPU work: configs, sorting, JSON. Leaves a core for the UI. */
    val cpu: CoroutineDispatcher = Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6), factory("cpu")).asCoroutineDispatcher()
}
