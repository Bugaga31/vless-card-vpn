package com.vlesscardvpn.core

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation closes the active HTTP call, not just its waiting coroutine. */
object CancellableProbe {
    suspend fun <T> run(block: ((() -> Unit) -> Unit) -> T): T = suspendCancellableCoroutine { cont ->
        val cancel = AtomicReference<(() -> Unit)?>(null)
        val worker = CoroutineScope(Bg.io).launch {
            try {
                val result = runInterruptible {
                    block { hook -> cancel.set(hook); if (!cont.isActive) hook() }
                }
                cont.resume(result)
            } catch (e: Throwable) { cont.resumeWithException(e) }
            finally { cancel.set(null) }
        }
        cont.invokeOnCancellation { runCatching { cancel.get()?.invoke() }; worker.cancel() }
    }
}
