package com.vlesscardvpn.core

/** The core races equivalent encrypted resolvers for an actual answer, not a TCP ping.
 * No persistent winner: a new core/network starts fresh. Manual DNS remains a user choice.
 */
object DnsPolicy {
    const val TIMEOUT_MS = 2500
    val ENCRYPTED = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "https://9.9.9.9/dns-query")
}

/** Bounded validation for the A/IN health query. Neither ANCOUNT alone nor a matching ID is proof. */
object DnsWire {
    fun query(host: String, id: Int): ByteArray {
        val labels = host.trimEnd('.').split('.')
        require(labels.all { it.length in 1..63 && it.all { c -> c.code in 33..126 } })
        require(labels.sumOf { it.length + 1 } + 1 <= 255)
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        labels.forEach { out.write(it.length); out.write(it.toByteArray(Charsets.US_ASCII)) }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }

    /** SOCKS UDP framing: fragments, reserved bits and short packets must never pass. */
    fun payload(packet: ByteArray, length: Int): ByteArray? {
        if (length !in 4..packet.size || packet[0] != 0.toByte() || packet[1] != 0.toByte() || packet[2] != 0.toByte()) return null
        val offset = when (packet[3].toInt() and 255) {
            1 -> 10
            4 -> 22
            3 -> { if (length < 5) return null; val n = packet[4].toInt() and 255; if (n == 0) return null; 7 + n }
            else -> return null
        }
        if (length < offset + 12) return null
        return packet.copyOfRange(offset, length)
    }

    fun hasAnswer(response: ByteArray, query: ByteArray): Boolean = try {
        val r = Reader(response); val q = Reader(query)
        require(r.u16(0) == q.u16(0))
        val flags = r.u16(2)
        require(flags and 0x8000 != 0 && flags and 0x7800 == 0 && flags and 0x0200 == 0 && flags and 15 == 0)
        require(r.u16(4) == 1 && q.u16(4) == 1)
        val wanted = q.name(12); val question = r.name(12)
        require(wanted.first == question.first && q.u16(wanted.second) == 1 && q.u16(wanted.second + 2) == 1)
        require(r.u16(question.second) == 1 && r.u16(question.second + 2) == 1)
        val answers = r.u16(6)
        val total = answers + r.u16(8) + r.u16(10)
        require(answers > 0 && total <= 128)
        var pos = question.second + 4
        val aliases = mutableMapOf<String, String>(); val addresses = mutableSetOf<String>()
        repeat(total) { index ->
            val owner = r.name(pos); pos = owner.second
            val type = r.u16(pos); val clazz = r.u16(pos + 2); val size = r.u16(pos + 8)
            pos += 10; val end = pos + size; require(end <= response.size)
            if (index < answers && clazz == 1) when (type) {
                1 -> { require(size == 4); addresses += owner.first }
                5 -> { val target = r.name(pos); require(target.second == end); aliases[owner.first] = target.first }
            }
            pos = end
        }
        require(pos == response.size)
        var name = wanted.first
        var found = name in addresses
        val seen = mutableSetOf<String>()
        repeat(16) { if (!found && seen.add(name)) { name = aliases[name] ?: name; found = name in addresses } }
        found
    } catch (_: IllegalArgumentException) { false }

    private class Reader(val data: ByteArray) {
        fun u8(pos: Int): Int { require(pos in data.indices); return data[pos].toInt() and 255 }
        fun u16(pos: Int) = (u8(pos) shl 8) or u8(pos + 1)
        fun name(start: Int): Pair<String, Int> {
            var pos = start; var end = -1; var bytes = 1
            val labels = mutableListOf<String>(); val visited = mutableSetOf<Int>()
            while (true) {
                require(visited.add(pos) && visited.size <= 128)
                val len = u8(pos)
                if (len and 0xc0 == 0xc0) {
                    val target = ((len and 63) shl 8) or u8(pos + 1)
                    require(target >= 12 && target < pos)
                    if (end < 0) end = pos + 2
                    pos = target; continue
                }
                require(len <= 63)
                pos++
                if (len == 0) return labels.joinToString(".") to if (end < 0) pos else end
                require(pos + len <= data.size)
                bytes += len + 1; require(bytes <= 255)
                val label = data.copyOfRange(pos, pos + len)
                require(label.all { (it.toInt() and 255) in 33..126 })
                labels += label.toString(Charsets.US_ASCII).lowercase(java.util.Locale.ROOT)
                pos += len
            }
        }
    }
}
