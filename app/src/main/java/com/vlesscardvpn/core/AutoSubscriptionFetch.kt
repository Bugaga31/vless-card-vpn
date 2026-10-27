package com.vlesscardvpn.core

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** At most two public mirrors raced per source; cancellation closes the actual HTTP call. */
object AutoSubscriptionFetch {
    private val http by lazy { OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS).callTimeout(AutoDiscoveryPolicy.SOURCE_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false).build() }

    suspend fun <T> firstUseful(urls: List<String>, fetch: suspend (String) -> T?): T? = coroutineScope {
        val candidates = urls.distinct().take(2)
        if (candidates.isEmpty()) return@coroutineScope null
        val responses = Channel<T?>(candidates.size)
        val jobs = candidates.map { url -> launch {
            val value = try { fetch(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            responses.send(value)
        } }
        try {
            repeat(candidates.size) { responses.receive()?.let { return@coroutineScope it } }
            null
        } finally { jobs.forEach { it.cancel() }; responses.close() }
    }

    suspend fun text(url: String): String? = suspendCancellableCoroutine { continuation ->
        val call = runCatching { http.newCall(Request.Builder().url(url).header("User-Agent", "VLESS-Card-Auto").build()) }.getOrNull()
        if (call == null) { continuation.resume(null); return@suspendCancellableCoroutine }
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val text = runCatching { response.use { r ->
                    val body = r.body
                    if (!r.isSuccessful || body == null || body.contentLength() > AutoDiscoveryPolicy.MAX_BYTES) null
                    else String(BoundedInput.read(body.byteStream(), AutoDiscoveryPolicy.MAX_BYTES), Charsets.UTF_8)
                } }.getOrNull()
                if (continuation.isActive) continuation.resume(text)
            }
        })
    }
}
