package com.vlesscardvpn.xraytest

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

class TcpHttpHeaderTest {
    private val base = "vless://11111111-1111-4111-8111-111111111111@example.org:443"
    private fun reject(extra: String) {
        try { XrayConfig.parse("$base?security=tls&$extra"); fail("Must reject invalid HTTP header") }
        catch (_: ProfileError) { }
    }
    private fun stream(extra: String): JSONObject = JSONObject(XrayConfig.build(XrayConfig.parse("$base?security=tls&$extra"), 10808))
        .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
    @Test fun tcpHttpHostAndPathsPreserved() {
        val s = stream("type=tcp&headerType=http&host=one.example,two.example&path=%2Fa,%2Fb%3Fx%3D1")
        val header = s.getJSONObject("tcpSettings").getJSONObject("header")
        val request = header.getJSONObject("request")
        assertEquals("http", header.getString("type"))
        assertEquals("one.example", request.getJSONObject("headers").getJSONArray("Host").getString(0))
        assertEquals("two.example", request.getJSONObject("headers").getJSONArray("Host").getString(1))
        assertEquals("/b?x=1", request.getJSONArray("path").getString(1))
        assertEquals("tls", s.getString("security"))
        assertFalse(s.getJSONObject("tlsSettings").getBoolean("allowInsecure"))
    }
    @Test fun tcpHttpDefaults() {
        val request = stream("headerType=http").getJSONObject("tcpSettings").getJSONObject("header").getJSONObject("request")
        assertEquals("GET", request.getString("method"))
        assertEquals("/", request.getJSONArray("path").getString(0))
        assertFalse(request.has("headers"))
    }
    @Test fun rawAliasSupportsHttpHeader() { assertEquals("http", stream("type=raw&headerType=http").getJSONObject("tcpSettings").getJSONObject("header").getString("type")) }
    @Test fun noHeaderLeavesPlainTcpUnchanged() { assertFalse(stream("type=tcp&headerType=none").has("tcpSettings")) }
    @Test fun httpHeaderRejectedOnWebSocket() { reject("type=ws&headerType=http") }
    @Test fun unknownHeaderRejected() { reject("headerType=unknown") }
    @Test fun headerControlCharactersRejected() { reject("headerType=http&host=example.org%0D%0AX-Test%3Ayes") }
    @Test fun invalidHttpPathRejected() { reject("headerType=http&path=not-absolute") }
    @Test fun controlOnlyPathRejected() { reject("headerType=http&path=%0D%0A") }
}
