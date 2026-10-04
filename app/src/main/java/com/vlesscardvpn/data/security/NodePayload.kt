package com.vlesscardvpn.data.security

import com.vlesscardvpn.domain.VlessConfig
import org.json.JSONObject

/** One authenticated payload preserves every connection parameter, including transport paths. */
object NodePayload {
    fun encode(c: VlessConfig): String = JSONObject().apply {
        put("name", c.name); put("address", c.address); put("port", c.port); put("uuid", c.uuid)
        put("protocolType", c.protocolType); put("flow", c.flow); put("security", c.security)
        put("sni", c.sni); put("fingerprint", c.fingerprint); put("publicKey", c.publicKey)
        put("shortId", c.shortId); put("remark", c.remark); put("country", c.country); put("source", c.source)
        put("transport", c.transport); put("wsHost", c.wsHost); put("wsPath", c.wsPath); put("serviceName", c.serviceName)
    }.toString()
    fun decode(base: VlessConfig, text: String): VlessConfig {
        try {
            val o = JSONObject(text)
            return base.copy(name = o.getString("name"), address = o.getString("address"), port = o.getInt("port"),
                uuid = o.getString("uuid"), protocolType = o.getString("protocolType"), flow = o.getString("flow"),
                security = o.getString("security"), sni = o.getString("sni"), fingerprint = o.getString("fingerprint"),
                publicKey = o.getString("publicKey"), shortId = o.getString("shortId"), remark = o.getString("remark"),
                country = o.getString("country"), source = o.getString("source"), transport = o.getString("transport"),
                wsHost = o.getString("wsHost"), wsPath = o.getString("wsPath"), serviceName = o.getString("serviceName"))
        } catch (_: Exception) { throw SecurityException("Защищённая конфигурация повреждена") }
    }
}
