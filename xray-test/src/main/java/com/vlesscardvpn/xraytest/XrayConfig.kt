package com.vlesscardvpn.xraytest

import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class ProfileError(val explanation: String) : IllegalArgumentException(explanation)
data class Node(val host: String, val port: Int, val id: String, val params: Map<String, String>)

object XrayConfig {
    fun parse(raw: String): Node {
        fun bad(s: String): Nothing = throw ProfileError(s)
        if (raw.length > 16384) bad("Слишком длинная ссылка")
        val u = try { URI(raw.trim()) } catch (_: Exception) { bad("Некорректная ссылка") }
        if (u.scheme != "vless") bad("В первом тесте поддерживаются только VLESS-ссылки")
        val id = u.userInfo ?: bad("Отсутствует UUID")
        if (!id.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))) bad("Некорректный UUID")
        try { UUID.fromString(id) } catch (_: Exception) { bad("Некорректный UUID") }
        val host = u.host?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() } ?: bad("Отсутствует адрес сервера")
        if (u.port !in 1..65535) bad("Некорректный порт")
        val q = linkedMapOf<String, String>()
        try {
            u.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.forEach {
                val parts = it.split('=', limit = 2)
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                if (q.put(key, value) != null) bad("Повторяющийся параметр")
            }
        } catch (e: ProfileError) { throw e } catch (_: Exception) { bad("Некорректные параметры") }
        val accepted = setOf("security", "type", "encryption", "flow", "sni", "serverName", "fp", "pbk", "sid", "spx", "path", "host", "serviceName", "mode", "alpn", "headerType")
        if (q.keys.any { it !in accepted }) bad("В ссылке есть неподдерживаемые параметры; не изменяйте их наугад")
        val security = q["security"] ?: "none"
        if (security !in setOf("tls", "reality")) bad("Для теста нужен VLESS с TLS или REALITY")
        val transport = q["type"] ?: "tcp"
        if (transport !in setOf("tcp", "raw", "ws", "grpc")) bad("В первом тесте поддерживаются TCP, WebSocket и gRPC")
        if (q.getOrDefault("encryption", "none") != "none") bad("Этот режим VLESS encryption пока не поддерживается")
        val headerType = q.getOrDefault("headerType", "none")
        if (headerType !in setOf("none", "http")) bad("Неизвестный тип TCP-заголовка")
        if (headerType == "http") {
            if (transport !in setOf("tcp", "raw")) bad("headerType=http поддерживается только для TCP/raw")
            val hostHeader = q["host"].orEmpty()
            val rawPaths = q["path"].orEmpty()
            if ((hostHeader + rawPaths).any { it.code < 32 || it.code == 127 }) bad("Управляющие символы в HTTP-заголовке или пути")
            val paths = rawPaths.ifBlank { "/" }
            if (hostHeader.isNotBlank() && hostHeader.split(',').any { it.trim().isEmpty() || it.trim().any { c -> c.isWhitespace() } }) bad("Некорректный HTTP Host")
            if (paths.split(',').any { !it.trim().startsWith("/") || it.trim().any { c -> c.isWhitespace() } }) bad("HTTP-путь должен начинаться с / и не содержать пробелов")
        }
        if (q.getOrDefault("mode", "gun") != "gun") bad("Этот режим транспорта пока не поддерживается")
        val flow = q["flow"].orEmpty()
        if (flow !in setOf("", "xtls-rprx-vision")) bad("Этот flow пока не поддерживается")
        if (flow.isNotEmpty() && transport !in setOf("tcp", "raw")) bad("Vision требует TCP")
        if (security == "reality") {
            if (q["pbk"].isNullOrBlank() || (q["sni"] ?: q["serverName"]).isNullOrBlank()) bad("Для REALITY нужны public key и SNI")
            val sid = q["sid"].orEmpty()
            if (sid.length > 16 || sid.length % 2 != 0 || !sid.matches(Regex("[0-9a-fA-F]*"))) bad("Некорректный short ID")
        }
        return Node(host, u.port, id, q)
    }

    fun parseList(input: String): List<Node> {
        if (input.length > 262144) throw ProfileError("Слишком большой список")
        val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (lines.isEmpty() || lines.size > 50) throw ProfileError("Добавьте от 1 до 50 VLESS-ссылок, по одной на строку")
        return lines.mapIndexed { i, line ->
            try { parse(line) } catch (e: ProfileError) { throw ProfileError("Строка ${i + 1}: ${e.explanation}") }
        }.distinct()
    }

    fun build(n: Node, socksPort: Int): String {
        val q = n.params
        val transport = q["type"] ?: "tcp"
        val sec = q.getValue("security")
        val stream = JSONObject().put("network", transport).put("security", sec)
        val tls = JSONObject().put("serverName", q["sni"] ?: q["serverName"] ?: n.host)
            .put("fingerprint", q["fp"] ?: "chrome")
        if (sec == "reality") {
            tls.put("publicKey", q.getValue("pbk")).put("shortId", q["sid"].orEmpty()).put("spiderX", q["spx"] ?: "/")
            stream.put("realitySettings", tls)
        } else {
            tls.put("allowInsecure", false)
            q["alpn"]?.takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(','))) }
            stream.put("tlsSettings", tls)
        }
        if (q["headerType"] == "http") {
            val request = JSONObject().put("version", "1.1").put("method", "GET")
                .put("path", JSONArray(q["path"].orEmpty().ifBlank { "/" }.split(',').map { it.trim() }))
            q["host"]?.takeIf { it.isNotBlank() }?.let {
                request.put("headers", JSONObject().put("Host", JSONArray(it.split(',').map { host -> host.trim() })))
            }
            stream.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", request)))
        }
        when (transport) {
            "ws" -> stream.put("wsSettings", JSONObject().put("path", q["path"] ?: "/").put("headers", JSONObject().apply { q["host"]?.let { put("Host", it) } }))
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", q["serviceName"] ?: q["path"].orEmpty()).put("multiMode", false))
        }
        val user = JSONObject().put("id", n.id).put("encryption", "none").put("flow", q["flow"].orEmpty())
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vless").put("streamSettings", stream)
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", n.host).put("port", n.port).put("users", JSONArray().put(user)))))
            .put("mux", JSONObject().put("enabled", false))

        val directOutbound = JSONObject().put("tag", "direct").put("protocol", "freedom")
        val blockOutbound = JSONObject().put("tag", "block").put("protocol", "blackhole")

        val dnsConfig = JSONObject()
            .put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))
            .put("queryStrategy", "UseIPv4")

        return JSONObject().put("log", JSONObject().put("loglevel", "none"))
            .put("dns", dnsConfig)
            .put("stats", JSONObject())
            .put("inbounds", JSONArray()
                .put(JSONObject().put("tag", "tun").put("protocol", "tun").put("settings", JSONObject().put("name", "xray0").put("MTU", 1400)))
                .put(JSONObject().put("tag", "probe").put("listen", "127.0.0.1").put("port", socksPort).put("protocol", "socks").put("settings", JSONObject().put("auth", "noauth").put("udp", false))))
            .put("outbounds", JSONArray().put(outbound).put(directOutbound).put(blockOutbound))
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()))
            .toString()
    }
}

/** Only HTTPS results through the selected Xray outbound count, not TCP reachability. */
object AutoPolicy {
    fun eligible(results: List<Boolean>) = results.isNotEmpty() && results.all { it }
    fun shouldSwitch(auto: Boolean, failures: Int, elapsedMs: Long) = auto && failures >= 2 && elapsedMs >= 20000
}
