package com.vlesscardvpn.model

/** Minimal Base64 (standard + URL-safe, padding optional) usable on Android 7 and in JVM tests. */
object Base64 {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    fun decode(input: String): ByteArray? {
        val s = input.trim().replace('-', '+').replace('_', '/').trimEnd('=')
        if (s.isEmpty() || s.any { STD.indexOf(it) < 0 }) return null
        if (s.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
        for (c in s) { buf = (buf shl 6) or STD.indexOf(c); bits += 6; if (bits >= 8) { bits -= 8; out.write((buf shr bits) and 0xFF) } }
        return out.toByteArray()
    }
    fun decodeToString(input: String): String? = decode(input)?.let { b ->
        val d = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        runCatching { d.decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrNull()
    }
    fun encode(text: String): String = encode(text.toByteArray(Charsets.UTF_8))
    fun encode(b: ByteArray): String {
        val sb = StringBuilder(); var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i); var v = 0
            for (k in 0 until 3) v = (v shl 8) or (if (k < n) b[i + k].toInt() and 0xFF else 0)
            for (k in 0 until 4) sb.append(if (k <= n) STD[(v shr (18 - 6 * k)) and 63] else '=')
            i += 3
        }
        return sb.toString()
    }
}
