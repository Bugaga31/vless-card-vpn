package com.vlesscardvpn.core

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation closes every active HTTP/socket hook, not just the last registered operation. */
object CancellableProbe {
    /** Transfer a started long-lived core to its session. Ordinary run closes hooks
     * on success too, so registering core.stop there destroys the core before measure.
     * withContext checks cancellation on return; failures/cancellation stop before reuse.
     */
    suspend fun startRetained(start: () -> Unit, stop: () -> Unit) {
        try { withContext(Bg.io) { runInterruptible { start() } } }
        catch (e: Throwable) { stop(); throw e }
    }

    suspend fun <T> run(block: ((() -> Unit) -> Unit) -> T): T = suspendCancellableCoroutine { cont ->
        val resources = ProbeCancellation()
        val worker = CoroutineScope(Bg.io).launch {
            try {
                val result = runInterruptible {
                    block { hook -> resources.add(hook); if (!cont.isActive) resources.close() }
                }
                cont.resume(result)
            } catch (e: Throwable) { cont.resumeWithException(e) }
            finally { resources.close() }
        }
        cont.invokeOnCancellation { resources.close(); worker.cancel() }
    }
}
