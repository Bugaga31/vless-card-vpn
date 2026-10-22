package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** Page reachability only. Never MTProto, login, calls or video playback evidence. */
object ServiceWebProbe {
    const val BUDGET_MS = 3000L
    const val SAMPLE_BYTES = 1024
    val URLS = listOf("https://web.telegram.org/", "https://www.youtube.com/")
    data class Result(val telegram: Boolean? = null, val youtube: Boolean? = null)

    /** The caller must supply its route-bound SOCKS client with redirects disabled. */
    fun check(budgetMs: Long = BUDGET_MS, call: (Request) -> Call): Result {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
        if (budgetMs <= 0) return Result()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceAtMost(BUDGET_MS))
        val gate = java.lang.Object()
        val values = arrayOfNulls<Boolean>(2)
        val calls = mutableListOf<Call>()
        var pending = 2
        var closed = false
        fun complete(index: Int, value: Boolean?) = synchronized(gate) {
            if (!closed && System.nanoTime() < deadline) values[index] = value
            pending--; gate.notifyAll()
        }
        try {
            URLS.forEachIndexed { index, url ->
                if (System.nanoTime() >= deadline) return@forEachIndexed
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
                try {
                    val active = call(Request.Builder().url(url).header("Cache-Control", "no-cache, no-store")
                        .header("Accept-Encoding", "identity").build())
                    calls += active
                    active.timeout().timeout((deadline-System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    active.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) = complete(index, null)
                        override fun onResponse(call: Call, response: Response) {
                            var value: Boolean? = null
                            try {
                                response.use { r ->
                                    // Reject redirects, challenge/denial pages, empty replies and changed origins.
                                    if (r.request.url != active.request().url || !r.request.url.isHttps) return@use
                                    if (r.code !in 200..299) { value = false; return@use }
                                    if (r.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase() != "text/html") return@use
                                    val input = r.body?.byteStream() ?: return@use
                                    val buf = ByteArray(SAMPLE_BYTES)
                                    var n = 0
                                    while (n < buf.size && System.nanoTime() < deadline) {
                                        val count = input.read(buf, n, buf.size-n)
                                        if (count < 0) break
                                        n += count
                                    }
                                    val html = String(buf, 0, n, Charsets.UTF_8).lowercase()
                                    if (n == SAMPLE_BYTES && ("<!doctype html" in html || "<html" in html)) value = true
                                }
                            } catch (_: Exception) { value = null }
                            complete(index, value)
                        }
                    })
                } catch (_: Exception) { complete(index, null) }
            }
            synchronized(gate) {
                while (pending > 0) {
                    val left = deadline-System.nanoTime()
                    if (left <= 0) break
                    try { TimeUnit.NANOSECONDS.timedWait(gate, left) }
                    catch (_: InterruptedException) {
                        Thread.currentThread().interrupt(); throw InterruptedIOException("cancelled")
                    }
                }
                return Result(values[0], values[1])
            }
        } finally {
            synchronized(gate) { closed = true }
            calls.forEach { it.cancel() }
        }
    }
}
