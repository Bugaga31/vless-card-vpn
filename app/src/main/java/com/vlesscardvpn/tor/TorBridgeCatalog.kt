package com.vlesscardvpn.tor

import java.net.URI
import okio.ByteString.Companion.decodeBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Opt-in manual or scheduled import; external bridge lists are untrusted suggestions, not working-route evidence. */
object TorBridgeCatalog {
    const val SOURCE = "https://raw.githack.com/igareck/vpn-configs-for-russia/main/TOR-BRIDGES/TOR_BRIDGES_TOP100.txt"
    const val MAX_BYTES = 262144
    data class Parsed(val lines: List<String>, val rejected: Int) {
        fun forTransport(transport: String) = lines.filter { it.startsWith("Bridge $transport ") }.take(12)
    }
    private val fingerprint=Regex("[A-Fa-f0-9]{40}")
    fun normalize(raw: String): String? {
        val line=raw.trim()
        if(line.isEmpty() || line.startsWith('#')) return null
        if(line.length>4096 || line.any { it.code<32 || it.code==127 }) return null
        val tokens=line.removePrefix("Bridge ").split(Regex(" +"))
        if(tokens.size<4 || tokens[0] !in setOf("obfs4","webtunnel") || !fingerprint.matches(tokens[2])) return null
        val endpoint=tokens[1];val port=endpoint.substringAfterLast(':').toIntOrNull() ?: return null
        val host=endpoint.substringBeforeLast(':').removeSurrounding("[","]")
        if(port !in 1..65535 || host.isBlank()) return null
        val ipv4=host.split('.')
        val validHost=if(':' in host) endpoint.startsWith('[') && Regex("[A-Fa-f0-9:]+").matches(host) && runCatching { java.net.InetAddress.getByName(host) is java.net.Inet6Address }.getOrDefault(false)
            else ipv4.size==4 && ipv4.all { it.toIntOrNull() in 0..255 }
        if(!validHost) return null
        if(tokens[0]=="obfs4") {
            val address=runCatching { if(':' in host) java.net.InetAddress.getByName(host)
                else java.net.InetAddress.getByAddress(ByteArray(4) { ipv4[it].toInt().toByte() }) }.getOrNull() ?: return null
            val bytes=address.address
            val sharedV4=bytes.size==4 && (bytes[0].toInt() and 255)==100 && (bytes[1].toInt() and 255) in 64..127
            val privateV6=bytes.size==16 && (bytes[0].toInt() and 254)==252
            if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress || sharedV4 || privateV6) return null
        }
        val params=tokens.drop(3)
        if(params.any { '=' !in it } || params.map { it.substringBefore('=') }.distinct().size!=params.size) return null
        if(tokens[0]=="obfs4") {
            if(params.any { it.substringBefore('=') !in setOf("cert","iat-mode") }) return null
            val cert=params.firstOrNull { it.startsWith("cert=") }?.substringAfter('=') ?: return null
            if(!Regex("[A-Za-z0-9+/]{70}={0,2}").matches(cert) || cert.decodeBase64()?.size!=52) return null
            if(params.firstOrNull { it.startsWith("iat-mode=") }?.substringAfter('=') !in setOf("0","1","2")) return null
        } else {
            if(params.any { it.substringBefore('=') !in setOf("url","ver") }) return null
            val url=params.firstOrNull { it.startsWith("url=") }?.substringAfter('=') ?: return null
            val u=runCatching { URI(url) }.getOrNull() ?: return null
            if(u.scheme!="https" || u.host.isNullOrBlank() || u.userInfo!=null || u.fragment!=null || u.port !in setOf(-1,443) || u.path.isNullOrEmpty()) return null
            val h=u.host.lowercase(java.util.Locale.ROOT)
            if(h=="localhost" || h.endsWith(".local") || h.endsWith(".localhost") || h.contains(':') || h.all { it.isDigit() || it=='.' }) return null
            if(params.firstOrNull { it.startsWith("ver=") }?.substringAfter('=')?.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))!=true) return null
        }
        return "Bridge "+tokens.joinToString(" ")
    }
    fun parse(text: String): Parsed {
        require(text.toByteArray(Charsets.UTF_8).size<=MAX_BYTES) { "Bridge list too large" }
        val raw=text.lineSequence().toList();require(raw.size<=1000) { "Too many bridge lines" }
        var rejected=0;val lines=linkedSetOf<String>()
        raw.filter { it.isNotBlank() && !it.trimStart().startsWith('#') }.forEach { line ->
            val normalized=normalize(line)
            if(normalized==null) rejected++ else lines+=normalized
        }
        return Parsed(lines.toList(),rejected)
    }
    fun selected(text: String, transport: String): List<String> {
        require(transport in setOf("none","obfs4","webtunnel")) { "Unsupported embedded Tor transport" }
        if(transport=="none") return emptyList()
        val parsed=parse(text);val result=parsed.forTransport(transport)
        require(result.isNotEmpty()) { "No valid bridges for selected transport" }
        return result
    }
    suspend fun fetch(): Parsed = withContext(Dispatchers.IO) {
        val client=OkHttpClient.Builder().callTimeout(8,TimeUnit.SECONDS).connectTimeout(4,TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        try {
            client.newCall(Request.Builder().url(SOURCE).header("Accept","text/plain").build()).execute().use { r ->
                check(r.code==200) { "Bridge source HTTP ${r.code}" }
                val input=r.body?.byteStream() ?: error("Empty bridge list")
                val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192)
                while(true) {
                    val n=input.read(buf);if(n<0) break
                    check(out.size()+n<=MAX_BYTES) { "Bridge list too large" };out.write(buf,0,n)
                }
                parse(out.toString("UTF-8"))
            }
        } finally { client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
    }
}
