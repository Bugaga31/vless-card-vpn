package com.vlesscardvpn.core

import java.net.InetAddress
import java.util.concurrent.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** DNS libc calls may ignore interruption. Isolate them from the 96 probe workers.
 * Four daemon workers + 32 waiting jobs is a hard resource bound, not a DNS cache.
 * Timeout/rejection/failure is unknown, never a dead-server verdict; late results are discarded.
 */
object ProbeDns {
    private val executor=ThreadPoolExecutor(4,4,30L,TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(32),{r->Thread({runCatching {android.os.Process.setThreadPriority(10)};r.run()},"vc-probe-dns").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    suspend fun resolve(host:String,budgetMs:Long,lookup:(String)->List<InetAddress> = {InetAddress.getAllByName(it).toList()}):List<InetAddress>? {
        if(host.isBlank() || budgetMs<=0) return null
        NumericProbeAddress.parse(host)?.let { return listOf(it) }
        return withTimeoutOrNull(budgetMs.coerceAtMost(60000L)) {
            suspendCancellableCoroutine { continuation ->
                val task=FutureTask<Unit> {
                    val result=runCatching {lookup(host).distinctBy {it.hostAddress}.take(8)}.getOrNull()?.takeIf {it.isNotEmpty()}
                    if(continuation.isActive) continuation.resume(result)
                }
                continuation.invokeOnCancellation {task.cancel(true);executor.remove(task)}
                try {executor.execute(task)} catch(_:RejectedExecutionException) {if(continuation.isActive) continuation.resume(null)}
            }
        }
    }
}
