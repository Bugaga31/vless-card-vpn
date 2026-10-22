package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independent HTTPS controls, never Telegram/YouTube proof. Max 3 x 64 KiB, no cookies/login. */
object HttpsTransferProbe {
    const val SAMPLE_BYTES = 65536
    const val REQUIRED_HOSTS = 2
    val URLS = listOf("https://speed.cloudflare.com/__down?bytes=65536",
        "https://cachefly.cachefly.net/1mb.test", "https://proof.ovh.net/files/1Mb.dat")
    data class Result(val hosts: Int = 0, val bytes: Int = 0, val kbps: Int = 0, val elapsedMs: Long = 0) {
        val passed: Boolean get() = hosts >= REQUIRED_HOSTS
    }
    fun check(urls: List<String> = URLS, now: () -> Long = System::nanoTime, budgetMs: Long = 4000,
              call: (Request) -> Call): Result {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
        if (budgetMs <= 0) return Result()
        val started = now()
        // A separate real monotonic clock keeps the gate bounded even in tests with a fake metrics clock.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceAtMost(4000))
        val controls = urls.mapNotNull { it.toHttpUrlOrNull() }
            .filter { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }.distinctBy { it.host }.take(3)
        val gate = java.lang.Object()
        val closed = AtomicBoolean(false)
        val calls = mutableListOf<Call>()
        val hosts = HashSet<String>()
        var pending = controls.size
        var bytes = 0
        var successfulNanos = 0L
        try {
            // Start independent controls without letting a stalled first origin starve the other two.
            // The caller's SOCKS client, cancellation hooks and bounded OkHttp dispatcher are retained.
            for (url in controls) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
                if (synchronized(gate) { hosts.size >= REQUIRED_HOSTS } || System.nanoTime() >= deadline) break
                val sampleStarted = now()
                val completed = AtomicBoolean(false)
                fun complete(n: Int) {
                    if (!completed.compareAndSet(false, true)) return
                    synchronized(gate) {
                        if (!closed.get() && System.nanoTime() < deadline) {
                            bytes += n
                            if (n == SAMPLE_BYTES) {
                                hosts += url.host
                                successfulNanos += (now() - sampleStarted).coerceAtLeast(1)
                            }
                        }
                        pending--
                        gate.notifyAll()
                    }
                }
                try {
                    val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
                        .header("Cache-Control", "no-cache, no-store").header("Range", "bytes=0-65535").build()
                    val active = call(request)
                    calls += active
                    active.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) { complete(0) }
                        override fun onResponse(call: Call, response: Response) {
                            var n = 0
                            try {
                                response.use { r ->
                                    if (closed.get() || !ProbeResponsePolicy.download(r.code, r.header("Content-Type"), r.header("Content-Encoding"))) return@use
                                    val stream = r.body?.byteStream() ?: return@use
                                    val buf = ByteArray(8192)
                                    while (n < SAMPLE_BYTES && !closed.get() && System.nanoTime() < deadline) {
                                        val count = stream.read(buf, 0, minOf(buf.size, SAMPLE_BYTES - n))
                                        if (count < 0) break
                                        n += count
                                    }
                                }
                            } catch (_: Exception) { n = 0 }
                            complete(n)
                        }
                    })
                } catch (_: Exception) { complete(0) }
            }
            synchronized(gate) {
                while (pending > 0 && hosts.size < REQUIRED_HOSTS) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) break
                    TimeUnit.NANOSECONDS.timedWait(gate, left)
                }
                closed.set(true) // no late callback may change the returned receipt
                val elapsed = ((now() - started) / 1_000_000).coerceAtLeast(1)
                val kbps = if (hosts.size >= REQUIRED_HOSTS)
                    (hosts.size * SAMPLE_BYTES * 8L / (successfulNanos / 1_000_000).coerceAtLeast(1))
                        .coerceIn(1, Int.MAX_VALUE.toLong()).toInt() else 0
                return Result(hosts.size, bytes, kbps, elapsed)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled")
        } finally {
            closed.set(true)
            calls.forEach { runCatching { it.cancel() } }
        }
    }
}
