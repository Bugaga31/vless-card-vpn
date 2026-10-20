package com.vlesscardvpn.core

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A probe can own parallel HTTPS/player calls; cancelling only the LAST registered call leaks the others. */
internal class ProbeCancellation {
    private val lock = Any()
    private val hooks = mutableListOf<() -> Unit>()
    private var cancelled = false
    private var finished = false
    fun register(hook: () -> Unit) {
        val closeNow = synchronized(lock) {
            if (cancelled || finished) true else { hooks += hook; false }
        }
        if (closeNow) runCatching(hook)
    }
    fun cancel() {
        val pending = synchronized(lock) {
            cancelled = true
            hooks.toList().also { hooks.clear() }
        }
        pending.forEach { runCatching(it) }
    }
    fun finish() { synchronized(lock) { finished = true; hooks.clear() } }
}

/** Parent cancellation closes ALL registered transports, including concurrently registered/late calls. */
object CancellableProbe {
    suspend fun <T> run(block: ((() -> Unit) -> Unit) -> T): T = suspendCancellableCoroutine { cont ->
        val cancellation = ProbeCancellation()
        val worker = CoroutineScope(Bg.io).launch {
            try {
                val result = runInterruptible { block(cancellation::register) }
                cont.resume(result)
            } catch (e: Throwable) { cont.resumeWithException(e) }
            finally { cancellation.finish() }
        }
        cont.invokeOnCancellation { cancellation.cancel(); worker.cancel() }
    }
}
