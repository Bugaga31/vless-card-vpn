package com.vlesscardvpn.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded app-priority executors. Explicit checks are no longer deprioritized for other applications.
 * ProbeConcurrencyPolicy bounds admitted routes; CPU work remains off the UI thread.
 */
object Bg {
    const val IO_THREADS = ProbeConcurrencyPolicy.MAX + 96
    private val n = AtomicInteger()
    private fun factory(name: String) = ThreadFactory { r ->
        Thread({
            // Normal app priority; plain JVM (unit tests) has no android.os.Process
            runCatching { android.os.Process.setThreadPriority(0) }
            r.run()
        }, "vc-$name-${n.incrementAndGet()}").apply { isDaemon = true }
    }
    /** Blocking network probes (sockets, HTTP through local proxies). */
    // Room for ProbeConcurrencyPolicy.MAX blocking route probes plus ordinary I/O. Threads are created
    // lazily and idle ones exit after 30 s, so the old 96-thread cap no longer hides the user ceiling.
    val io: CoroutineDispatcher = ThreadPoolExecutor(IO_THREADS, IO_THREADS, 30L, TimeUnit.SECONDS,
        LinkedBlockingQueue(), factory("io")).apply { allowCoreThreadTimeOut(true) }.asCoroutineDispatcher()
    /** CPU work: configs, sorting, JSON. Leaves a core for the UI. */
    val cpu: CoroutineDispatcher = Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6), factory("cpu")).asCoroutineDispatcher()
}
