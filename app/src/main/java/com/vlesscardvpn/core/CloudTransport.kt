package com.vlesscardvpn.core

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** PUBLIC control-plane data only. Never accepts arbitrary URLs or a private backup token. */
object CloudTransport {
    data class Download(val text: String?, val etag: String?, val unchanged: Boolean, val downloadedAt: Long, val checkedAt: Long)
    private val http by lazy { OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build() }
    fun urls(path: String): List<String> {
        require(path in setOf("speed.json", "cloud.json", "cloud-manifest.json"))
        return listOf("https://raw.githubusercontent.com/Bugaga31/vless-card-vpn/servers/$path",
            "https://cdn.jsdelivr.net/gh/Bugaga31/vless-card-vpn@servers/$path")
    }
    fun fetch(ctx: Context, path: String, etag: String? = null, valid: (String) -> Unit): Download {
        val urls = urls(path)
        fun request(client: OkHttpClient, url: String, tag: String?): Download {
            return client.newCall(Request.Builder().url(url).apply { if (tag != null) header("If-None-Match", tag) }.build()).execute().use { r ->
                if (r.code == 304 && tag != null) return@use Download(null, tag, true, 0, System.currentTimeMillis())
                check(r.code == 200) { "Cloud HTTP ${r.code}" }
                val text = r.body!!.byteStream().use { String(BoundedInput.read(it, 2 * 1024 * 1024)) }
                val downloadedAt = System.currentTimeMillis()
                valid(text)
                Download(text, if (url == urls.first()) r.header("ETag") else null, false, downloadedAt, System.currentTimeMillis())
            }
        }
        for ((client,url,tag) in listOf(Triple(http,urls[0],etag),Triple(http,urls[1],null))) {
            runCatching { request(client,url,tag) }.getOrNull()?.let { return it }
        }
        val ports = (listOfNotNull(Tunnel.socks?.port, Tunnel.byeDpiPort) + Warp.extraSocks).distinct().take(3)
        for (port in ports) {
            runCatching { request(http.newBuilder().proxy(Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",port))).build(),urls[0],etag) }
                .getOrNull()?.let { return it }
        }
        // A temporary local ByeDPI fallback only when power policy and the user's auto-optimisation setting permit it.
        if (Store.state.value.settings.autoOptimize && PhonePower.refresh(ctx).allowLight) {
            val proxy = DpiProxy(ctx)
            try {
                if (proxy.available(DpiEngine.BYEDPI)) {
                    val port = kotlinx.coroutines.runBlocking { proxy.start(DpiStrategies.CASCADE, Store.state.value.settings.byeDpiSni) }
                    return request(http.newBuilder().proxy(Proxy(Proxy.Type.SOCKS,InetSocketAddress("127.0.0.1",port))).build(),urls[0],etag)
                }
            } finally { proxy.close() }
        }
        error("Public cloud unavailable; keep last known cache")
    }
}
