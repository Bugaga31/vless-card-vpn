package com.vlesscardvpn.core

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException

/** An interrupted parent must not quietly continue waiting for the other downloads. */
object ProbeTasks {
    fun <T> await(f: Future<T>?, timeoutMs: Long, fallback: T): T {
        if (f == null) return fallback
        return try { f.get(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
        catch (_: TimeoutException) { f.cancel(true); fallback }
        catch (_: ExecutionException) { fallback }
        catch (_: CancellationException) { fallback }
    }
    fun cancel(tasks: List<Future<*>>) { tasks.forEach { if (!it.isDone) it.cancel(true) } }
}
