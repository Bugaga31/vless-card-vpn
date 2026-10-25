package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DnsPolicyTest {
    private val q = DnsWire.query("www.google.com", 0x1234)
    private fun answer(query: ByteArray = q): ByteArray = query.copyOf().apply {
        this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1
    } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 8, 8, 8, 8)
    private fun dns(settings: Settings = Settings()) = JSONObject(XrayConfigBuilder.vpnConfig(emptyList(), settings.copy(mode = Mode.BYEDPI), 1080)).getJSONObject("dns")

    @Test fun validCompressedAnswer() { assertTrue(DnsWire.hasAnswer(answer(), q)) }
    @Test fun wrongIdRejected() { assertFalse(DnsWire.hasAnswer(answer().apply { this[0] = 0 }, q)) }
    @Test fun queryIsNotAnAnswer() { assertFalse(DnsWire.hasAnswer(q, q)) }
    @Test fun truncatedAnswerRejected() { assertFalse(DnsWire.hasAnswer(answer().apply { this[2] = 0x83.toByte() }, q)) }
    @Test fun opcodeRejected() { assertFalse(DnsWire.hasAnswer(answer().apply { this[2] = 0x89.toByte() }, q)) }
    @Test fun nxdomainNotPass() { assertFalse(DnsWire.hasAnswer(answer().apply { this[3] = 0x83.toByte() }, q)) }
    @Test fun servfailNotPass() { assertFalse(DnsWire.hasAnswer(answer().apply { this[3] = 0x82.toByte() }, q)) }
    @Test fun emptyAnswerNotPass() { assertFalse(DnsWire.hasAnswer(answer().apply { this[7] = 0 }, q)) }
    @Test fun everyTruncatedLengthRejectedWithoutException() {
        val a = answer(); (0 until a.size).forEach { assertFalse("length=$it", DnsWire.hasAnswer(a.copyOf(it), q)) }
    }
    @Test fun unrelatedQuestionRejected() { assertFalse(DnsWire.hasAnswer(answer(DnsWire.query("www.yandex.com", 0x1234)), q)) }
    @Test fun unrelatedOwnerRejected() { assertFalse(DnsWire.hasAnswer(answer().apply { this[q.size + 1] = 16 }, q)) }
    @Test fun pointerLoopRejected() { assertFalse(DnsWire.hasAnswer(answer().apply { this[q.size + 1] = q.size.toByte() }, q)) }
    @Test fun outOfBoundsPointerRejected() { assertFalse(DnsWire.hasAnswer(answer().apply { this[q.size + 1] = 0xff.toByte() }, q)) }
    @Test fun wrongClassRejected() { assertFalse(DnsWire.hasAnswer(answer().apply { this[q.size + 5] = 3 }, q)) }
    @Test fun shortRdataRejected() { assertFalse(DnsWire.hasAnswer(answer().apply { this[q.size + 11] = 3 }, q)) }
    @Test fun extraBytesRejected() { assertFalse(DnsWire.hasAnswer(answer() + byteArrayOf(0), q)) }
    @Test fun forgedAnswerCountRejected() { assertFalse(DnsWire.hasAnswer(answer().apply { this[6] = 127 }, q)) }
    @Test fun cnameOnlyNotPass() {
        val a = answer().copyOf(q.size) + byteArrayOf(0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 2, 0xc0.toByte(), 12)
        assertFalse(DnsWire.hasAnswer(a, q))
    }
    @Test fun cnameToAddressPasses() {
        val header = answer().copyOf(q.size).apply { this[7] = 2 }
        val cname = byteArrayOf(0xc0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 60, 0, 6, 3, 99, 100, 110, 0xc0.toByte(), 16)
        val owner = byteArrayOf(3, 99, 100, 110, 0xc0.toByte(), 16)
        val a = owner + byteArrayOf(0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 1, 1, 1)
        assertTrue(DnsWire.hasAnswer(header + cname + a, q))
    }
    @Test fun socksV4FrameValid() {
        val packet = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53) + answer()
        assertArrayEquals(answer(), DnsWire.payload(packet, packet.size))
    }
    @Test fun allShortSocksFramesRejected() { (0..21).forEach { assertNull(DnsWire.payload(ByteArray(30), it)) } }
    @Test fun fragmentedSocksFrameRejected() { val p = byteArrayOf(0, 0, 1, 1) + ByteArray(100); assertNull(DnsWire.payload(p, p.size)) }
    @Test fun unknownSocksAddressRejected() { val p = byteArrayOf(0, 0, 0, 7) + ByteArray(100); assertNull(DnsWire.payload(p, p.size)) }
    @Test fun emptySocksDomainRejected() { val p = byteArrayOf(0, 0, 0, 3, 0) + ByteArray(100); assertNull(DnsWire.payload(p, p.size)) }
    @Test fun oversizedSocksLengthRejected() { assertNull(DnsWire.payload(ByteArray(10), 200)) }
    @Test fun queryLengthChecked() {
        for (host in listOf("", "a..com", "a".repeat(64) + ".com", List(5) { "a".repeat(63) }.joinToString("."))) {
            try { DnsWire.query(host, 0); fail(host) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun automaticIsParallelAndEncryptedOnly() {
        val c = dns(); assertTrue(c.getBoolean("enableParallelQuery"))
        java.io.File("/tmp/dns-auto-config.json").writeText(JSONObject().put("dns", c).toString())
        val list = c.getJSONArray("servers"); assertEquals(3, list.length())
        for (i in 0 until list.length()) {
            val server = list.getJSONObject(i)
            assertEquals(DnsPolicy.ENCRYPTED[i], server.getString("address"))
            assertEquals(2500, server.getInt("timeoutMs"))
            assertEquals(setOf("address", "timeoutMs"), server.keys().asSequence().toSet())
        }
    }
    @Test fun manualChoiceNotRaced() {
        val c = dns(Settings(dnsAuto = false, dnsUrl = "https://9.9.9.9/dns-query"))
        assertFalse(c.getBoolean("enableParallelQuery")); assertEquals("https://9.9.9.9/dns-query", c.getJSONArray("servers").getString(0))
    }
    @Test fun torNeverFallsBackToPlainDns() {
        val c = dns(Settings(torAll = true, dnsAuto = false, ruDirect = false, ruDns = false))
        val list = c.getJSONArray("servers"); (0 until list.length()).forEach { assertTrue(list.getString(it).startsWith("https://")) }
    }
    @Test fun settingsRoundtripAndMigrationPreserveManualChoice() {
        assertTrue(Settings.fromJson(JSONObject()).dnsAuto)
        assertFalse(Settings.fromJson(JSONObject().put("dnsUrl", "https://9.9.9.9/dns-query")).dnsAuto)
        assertFalse(Settings.fromJson(Settings(dnsAuto = false).toJson()).dnsAuto)
        assertTrue(Settings.fromJson(Settings(dnsAuto = true).toJson()).dnsAuto)
    }
}
