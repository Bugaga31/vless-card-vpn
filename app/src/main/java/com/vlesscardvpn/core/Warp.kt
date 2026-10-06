package com.vlesscardvpn.core

import com.vlesscardvpn.model.Server
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Built-in Cloudflare WARP: own key pair (Curve25519), free account registration through the official client API,
 * then a WireGuard server per endpoint. The endpoints differ by IP range and port: operators often block only
 * the default 162.159.192.1:2408, and UDP noise masks hide the WireGuard handshake.
 */
object Warp {
    const val SOURCE = "warp"
    private const val API = "https://api.cloudflareclient.com/v0a2158/reg"
    /** Official WARP endpoints: IP ranges 162.159.192-195.x / 188.114.96-99.x, any of the client ports. */
    val ENDPOINTS = listOf(
        "162.159.192.1:2408", "188.114.97.1:4500", "162.159.193.10:500", "188.114.98.224:1701",
        "162.159.195.1:894", "188.114.96.1:878", "162.159.192.7:4500", "188.114.99.10:2408",
        "162.159.193.5:1002", "188.114.97.6:955", "162.159.195.9:7103", "188.114.98.11:3854",
    )

    data class Account(val privateKey: String, val peerKey: String, val v4: String, val v6: String, val reserved: String, val id: String,
                       val token: String = "", val plus: Boolean = false)

    // ---------------- X25519 (RFC 7748), enough for one key per registration
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)

    private fun le(b: ByteArray) = BigInteger(1, b.reversedArray())
    private fun toLe(n: BigInteger): ByteArray {
        val be = n.mod(P).toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return (ByteArray(32 - be.size) + be).reversedArray()
    }

    fun clamp(k: ByteArray): ByteArray = k.copyOf().also { it[0] = (it[0].toInt() and 248).toByte(); it[31] = ((it[31].toInt() and 127) or 64).toByte() }

    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        val k = le(clamp(scalar))
        val x1 = le(u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() })
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO; var x3 = x1; var z3 = BigInteger.ONE
        var swap = 0
        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) { x2 = x3.also { x3 = x2 }; z2 = z3.also { z3 = z2 } }
            swap = kt
            val a = x2.add(z2).mod(P); val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P); val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P); val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P); val cb = c.multiply(b).mod(P)
            x3 = da.add(cb).pow(2).mod(P)
            z3 = x1.multiply(da.subtract(cb).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P)
        }
        if (swap == 1) { x2 = x3; z2 = z3 }
        return toLe(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)))
    }

    fun publicKey(priv: ByteArray): ByteArray = x25519(priv, ByteArray(32).also { it[0] = 9 })

    fun newPrivateKey(): ByteArray = clamp(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private fun b64(b: ByteArray) = com.vlesscardvpn.model.Base64.encode(b)

    // ---------------- registration
    private val http by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    /** Registers a free WARP account. Tries directly, then through the running VPN (if the API is blocked). */
    fun register(): Account {
        val priv = newPrivateKey()
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val body = JSONObject().put("key", b64(publicKey(priv))).put("install_id", "").put("fcm_token", "").put("tos", tos)
            .put("type", "Android").put("locale", "en_US").toString()
        var err: Throwable? = null
        for (c in clients()) {
            val r = runCatching {
                c.newCall(Request.Builder().url(API).header("User-Agent", "okhttp/3.12.1").header("CF-Client-Version", "a-6.30-3596")
                    .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType())).build()).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    check(r.isSuccessful) { "WARP: HTTP ${r.code}" }
                    parse(JSONObject(text), b64(priv))
                }
            }
            r.onSuccess { return it }.onFailure { err = it }
        }
        throw err ?: IllegalStateException("WARP: нет ответа")
    }

    /** Local SOCKS ports of DPI-bypass engines to retry through when api.cloudflareclient.com is blocked. */
    @Volatile var extraSocks: List<Int> = emptyList()

    private fun socks(port: Int) = http.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))).build()

    /** Direct, then through the running VPN (login via the app's Authenticator), then through DPI-bypass engines. */
    private fun clients(): List<OkHttpClient> = buildList {
        add(http)
        Tunnel.socks?.let { add(socks(it.port)) }
        extraSocks.forEach { add(socks(it)) }
    }

    /**
     * Binds a WARP+ key to the account (PUT /reg/{id}/account). Returns null on success, else the API's error
     * (e.g. "Too many connected devices." — the key is used up, try another one).
     */
    fun applyLicense(a: Account, license: String): String? {
        val body = JSONObject().put("license", license).toString()
        var err = "нет ответа"
        for (c in clients()) {
            val r = runCatching {
                c.newCall(Request.Builder().url("$API/${a.id}/account").header("Authorization", "Bearer ${a.token}")
                    .header("User-Agent", "okhttp/3.12.1").header("CF-Client-Version", "a-6.30-3596")
                    .put(body.toRequestBody("application/json; charset=UTF-8".toMediaType())).build()).execute().use { r ->
                    val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                    if (r.isSuccessful && o.optBoolean("warp_plus", false)) null
                    else o.optJSONArray("errors")?.optJSONObject(0)?.optString("message") ?: "HTTP ${r.code}"
                }
            }
            r.onSuccess { if (it == null) return null; err = it; if (!it.startsWith("HTTP")) return it }
        }
        return err
    }

    /** User keys first, then the built-in ones (shuffled); stops at the first key that binds. Returns the key used. */
    fun upgrade(a: Account, own: List<String>, builtIn: Boolean, maxTries: Int = 12): Pair<String?, String> {
        val order = own + if (builtIn) WarpKeys.BUILT_IN.shuffled().filter { it !in own } else emptyList()
        var last = ""
        for (k in order.take(maxTries + own.size)) {
            val e = applyLicense(a, k) ?: return k to ""
            last = e
        }
        return null to last
    }

    fun parse(o: JSONObject, privateKey: String): Account {
        val cfg = o.getJSONObject("config")
        val peer = cfg.getJSONArray("peers").getJSONObject(0)
        val addr = cfg.getJSONObject("interface").getJSONObject("addresses")
        val clientId = cfg.optString("client_id")
        val reserved = runCatching { com.vlesscardvpn.model.Base64.decode(clientId)!!.take(3).joinToString(",") { (it.toInt() and 255).toString() } }.getOrDefault("")
        return Account(privateKey, peer.getString("public_key"), addr.getString("v4"), addr.optString("v6"), reserved, o.optString("id"), o.optString("token"))
    }

    /** One WireGuard server per endpoint for this account. */
    fun servers(a: Account, endpoints: List<String>): List<Server> = endpoints.map { ep ->
        val host = ep.substringBeforeLast(':'); val port = ep.substringAfterLast(':').toInt()
        Server(name = (if (a.plus) "WARP+ " else "WARP ") + ep, protocol = "wireguard", address = host, port = port, secret = a.privateKey, pbk = a.peerKey,
            localAddress = "${a.v4}/32", reserved = a.reserved, mtu = 1280, source = SOURCE)
    }
}
