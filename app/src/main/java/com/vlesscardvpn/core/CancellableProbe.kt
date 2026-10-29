package com.vlesscardvpn.core

import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation closes every active HTTP/socket hook, not just the last registered operation. */
object CancellableProbe {
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
