package com.vlesscardvpn.core

import java.util.concurrent.*

/** Same 12-worker ceiling as before, but slow downloads/player HTML cannot starve MTProto. */
internal class ProbeWorkerSet(workers: Int = 4, capacity: Int = 64) : AutoCloseable {
    private fun executor(label: String) = ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(capacity), { r -> Thread(r,"probe-$label").apply { isDaemon = true } },ThreadPoolExecutor.AbortPolicy())
    private val telegram = executor("telegram")
    private val media = executor("media")
    private val download = executor("download")
    private fun <T> submit(executor: ExecutorService, fallback: T, task: () -> T): Future<T> =
        try { executor.submit(Callable(task)) } catch (_: RejectedExecutionException) { CompletableFuture.completedFuture(fallback) }
    fun <T> telegram(fallback: T, task: () -> T) = submit(telegram,fallback,task)
    fun <T> media(fallback: T, task: () -> T) = submit(media,fallback,task)
    fun <T> download(fallback: T, task: () -> T) = submit(download,fallback,task)
    fun purge() { telegram.purge(); media.purge(); download.purge() }
    override fun close() { telegram.shutdownNow(); media.shutdownNow(); download.shutdownNow() }
}
