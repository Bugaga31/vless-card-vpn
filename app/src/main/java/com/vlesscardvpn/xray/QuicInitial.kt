package com.vlesscardvpn.xray

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A real QUIC v1 Initial packet (RFC 9000/9001) with a TLS ClientHello for [sni] — what a browser sends first for HTTP/3.
 * The TSPU can decrypt Initial packets (their keys come from the public connection ID) and read the SNI: our old
 * «шум под QUIC» was a QUIC header + random bytes, this one decrypts to a proper ClientHello of a whitelisted site
 * (idea from the «Киберпортал» WARP generator, its AmneziaWG I1 packet). Sent as noise before the WireGuard handshake.
 */
object QuicInitial {
    private val SALT = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")

    fun packet(sni: String, rnd: java.util.Random = java.security.SecureRandom()): ByteArray {
        val dcid = ByteArray(8).also { rnd.nextBytes(it) }
        val scid = ByteArray(8).also { rnd.nextBytes(it) }
        val hello = clientHello(sni, rnd)
        // CRYPTO frame (offset 0) + PADDING up to a 1200-byte datagram, as browsers do
        val crypto = byteArrayOf(0x06, 0x00) + varint(hello.size.toLong()) + hello
        val pnLen = 4
        val headLen = 1 + 4 + 1 + dcid.size + 1 + scid.size + 1 + 2 + pnLen
        val payloadLen = maxOf(crypto.size, 1200 - headLen - 16)
        val payload = crypto + ByteArray(payloadLen - crypto.size)
        val pn = rnd.nextInt(4).toLong()
        val length = pnLen + payload.size + 16
        val header = byteArrayOf((0xc0 or (pnLen - 1)).toByte(), 0, 0, 0, 1, dcid.size.toByte()) + dcid + byteArrayOf(scid.size.toByte()) + scid +
            byteArrayOf(0x00) + byteArrayOf((0x40 or (length shr 8)).toByte(), length.toByte()) + ByteArray(pnLen) { (pn shr (8 * (pnLen - 1 - it))).toByte() }
        val initial = hkdfExtract(SALT, dcid)
        val client = expandLabel(initial, "client in", 32)
        val key = expandLabel(client, "quic key", 16); val iv = expandLabel(client, "quic iv", 12); val hp = expandLabel(client, "quic hp", 16)
        val nonce = iv.copyOf().also { for (i in 0 until 8) it[11 - i] = (it[11 - i].toInt() xor (pn shr (8 * i)).toInt()).toByte() }
        val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(header) }
        val sealed = gcm.doFinal(payload)
        val out = header + sealed
        // header protection: a sample of the ciphertext 4 bytes after the packet number start
        val pnOff = header.size - pnLen
        val mask = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES")) }.doFinal(out.copyOfRange(pnOff + 4, pnOff + 20))
        out[0] = (out[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        for (i in 0 until pnLen) out[pnOff + i] = (out[pnOff + i].toInt() xor mask[1 + i].toInt()).toByte()
        return out
    }

    /** Xray noise items: the QUIC Initial as an exact packet, then [junk] small AmneziaWG-like packets. */
    fun noise(sni: String, junk: Int = 0): String {
        val p = packet(sni).joinToString("") { "%02x".format(it) }
        return "[" + (listOf("""{"type":"exp","packet":"<b $p>","delay":"2-5"}""") + List(junk) { """{"rand":"40-70","delay":"1-3"}""" }).joinToString(",") + "]"
    }

    fun clientHello(sni: String, rnd: java.util.Random): ByteArray {
        val name = sni.toByteArray()
        fun ext(type: Int, body: ByteArray) = u16(type) + u16(body.size) + body
        val keyShare = ByteArray(32).also { rnd.nextBytes(it) }
        val tp = tp(0x01, u32ish(30000)) + tp(0x04, u32ish(1048576)) + tp(0x05, u32ish(524288)) + tp(0x06, u32ish(524288)) +
            tp(0x07, u32ish(524288)) + tp(0x08, u32ish(100)) + tp(0x09, u32ish(100)) + tp(0x0f, ByteArray(8).also { rnd.nextBytes(it) })
        val exts = ext(0x0000, u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name) +
            ext(0x000a, u16(6) + u16(0x001d) + u16(0x0017) + u16(0x0018)) +
            ext(0x000d, u16(8) + u16(0x0403) + u16(0x0804) + u16(0x0401) + u16(0x0503)) +
            ext(0x0010, u16(3) + byteArrayOf(2) + "h3".toByteArray()) +
            ext(0x002b, byteArrayOf(2) + u16(0x0304)) +
            ext(0x002d, byteArrayOf(1, 1)) +
            ext(0x0033, u16(36) + u16(0x001d) + u16(32) + keyShare) +
            ext(0x0039, tp)
        val random = ByteArray(32).also { rnd.nextBytes(it) }
        val body = u16(0x0303) + random + byteArrayOf(0) + u16(6) + u16(0x1301) + u16(0x1302) + u16(0x1303) + byteArrayOf(1, 0) + u16(exts.size) + exts
        return byteArrayOf(1, (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
    }

    private fun tp(id: Int, v: ByteArray) = varint(id.toLong()) + varint(v.size.toLong()) + v
    private fun u32ish(v: Long) = varint(v)
    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    fun varint(v: Long): ByteArray = when {
        v < 64 -> byteArrayOf(v.toByte())
        v < 16384 -> byteArrayOf((0x40 or (v shr 8).toInt()).toByte(), v.toByte())
        v < 1073741824 -> byteArrayOf((0x80 or (v shr 24).toInt()).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        else -> ByteArray(8) { (v shr (8 * (7 - it))).toByte() }.also { it[0] = (it[0].toInt() or 0xc0).toByte() }
    }

    private fun hmac(key: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray) = hmac(salt, ikm)
    private fun expandLabel(secret: ByteArray, label: String, len: Int): ByteArray {
        val full = ("tls13 $label").toByteArray()
        val info = u16(len) + byteArrayOf(full.size.toByte()) + full + byteArrayOf(0)
        return hmac(secret, info + byteArrayOf(1)).copyOf(len)  // len ≤ 32: one block
    }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
