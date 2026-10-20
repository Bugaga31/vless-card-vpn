package com.vlesscardvpn.core

import okhttp3.Call
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Independent HTTPS controls, never Telegram/YouTube proof. Max 3 x 64 KiB, no cookies/login. */
object HttpsTransferProbe {
    const val SAMPLE_BYTES = 65536
    const val REQUIRED_HOSTS = 2
    val URLS = listOf("https://speed.cloudflare.com/__down?bytes=65536",
        "https://cachefly.cachefly.net/1mb.test", "https://proof.ovh.net/files/1Mb.dat")
    data class Result(val hosts: Int = 0, val bytes: Int = 0, val kbps: Int = 0, val elapsedMs: Long = 0) {
        val passed: Boolean get() = hosts >= REQUIRED_HOSTS
    }
    fun check(urls: List<String> = URLS, now: () -> Long = System::nanoTime,
              call: (Request) -> Call): Result {
        val started = now(); var bytes = 0; var successfulNanos = 0L; val hosts = HashSet<String>()
        // An endpoint error never blacklists a route. Try another independent TLS host.
        for (url in urls.distinctBy { it.toHttpUrlOrNull()?.host }.take(3)) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("cancelled")
            val parsed = url.toHttpUrlOrNull() ?: continue
            if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) continue
            val sampleStarted = now()
            try {
                call(Request.Builder().url(url).header("Accept-Encoding","identity")
                    .header("Cache-Control","no-cache, no-store").header("Range","bytes=0-65535").build())
                    .execute().use { r ->
                        if (!ProbeResponsePolicy.download(r.code,r.header("Content-Type"),r.header("Content-Encoding"))) return@use
                        val stream = r.body?.byteStream() ?: return@use
                        val buf = ByteArray(8192); var n = 0
                        while (n < SAMPLE_BYTES) {
                            val count = stream.read(buf,0,minOf(buf.size,SAMPLE_BYTES-n))
                            if (count < 0) break
                            n += count
                        }
                        bytes += n
                        if (n == SAMPLE_BYTES) { hosts += parsed.host; successfulNanos += (now()-sampleStarted).coerceAtLeast(1) }
                    }
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("cancelled")
            }
            if (hosts.size >= REQUIRED_HOSTS) break
        }
        val elapsed = ((now()-started)/1_000_000).coerceAtLeast(1)
        return Result(hosts.size,bytes,if (hosts.size >= REQUIRED_HOSTS) (hosts.size*SAMPLE_BYTES*8L / (successfulNanos/1_000_000).coerceAtLeast(1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1) else 0,elapsed)
    }
}
