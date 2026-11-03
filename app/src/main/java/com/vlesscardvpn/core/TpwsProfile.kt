package com.vlesscardvpn.core

import com.vlesscardvpn.model.Base64

/** Bounded argv for the bundled zapret tpws SOCKS engine, never nfqws/Lua, shell or file loading. */
object TpwsProfile {
    const val PREFIX = "TPWS#USER1:"
    val EXAMPLES = listOf(
        "--tlsrec=sniext+1 --split-pos=1,midsld --tamper-cutoff=n2",
        "--split-pos=1,host,midsld,endhost --disorder=tls --tamper-cutoff=n2",
        "--tlsrec=midsld --split-pos=1 --tamper-cutoff=n2"
    )
    private val markers = setOf("host", "endhost", "sld", "endsld", "midsld", "sniext")
    private fun position(s: String): Boolean {
        s.toIntOrNull()?.let { return it in -32768..32767 && it != 0 }
        val m = Regex("([a-z]+)([+-][0-9]{1,5})?").matchEntire(s) ?: return false
        return m.groupValues[1] in markers && (m.groupValues[2].isEmpty() ||
            m.groupValues[2].toIntOrNull()?.let { it in -32768..32767 } == true)
    }
    fun parse(line: String): Result<List<String>> = runCatching {
        require(line.length in 1..768 && line.none { it.isISOControl() }) { "tpws: строка 1–768 символов, без управляющих символов" }
        val tokens = line.trim().split(Regex("\\s+"))
        require(tokens.size in 1..8) { "tpws: не более 8 параметров" }
        val seen = HashSet<String>()
        var splits = false; var records = false; var needsSplit = false
        val out = tokens.map { t ->
            require(t.startsWith("--")) { "Нужны параметры tpws --имя=значение; команды nfqws/zapret2/ByeDPI здесь не выполняются" }
            val name = t.substringBefore('='); val v = t.substringAfter('=', "")
            require(seen.add(name)) { "tpws: повтор параметра $name" }
            when (name) {
                "--split-pos" -> {
                    val p = v.split(','); require(p.size in 1..8 && p.all(::position) && p.distinct().size == p.size) { "tpws: до 8 различных позиций TCP, например 1,host,midsld" }; splits = true
                }
                "--tlsrec" -> { require(position(v)) { "tpws: неверная позиция TLS-записи" }; records = true }
                "--disorder", "--oob" -> { require(t == name || v == "tls") { "tpws: допустим флаг без значения или =tls" }; needsSplit = true }
                "--oob-data" -> require(Regex("0x[0-9a-fA-F]{2}").matches(v)) { "tpws: OOB-байт в формате 0x00" }
                "--tamper-cutoff" -> require(v == "n2") { "tpws: эта редакция ограничивает преобразование первым блоком: n2" }
                else -> throw IllegalArgumentException("tpws: параметр $name не разрешён; файлы, listen/порт, NFQUEUE и Lua запрещены")
            }; t
        }.toMutableList()
        require(splits || records) { "tpws: нужен split-pos или tlsrec" }
        require(!needsSplit || splits) { "tpws: disorder/OOB требуют split-pos" }
        require("--oob-data" !in seen || "--oob" in seen) { "tpws: oob-data требует oob" }
        if ("--tamper-cutoff" !in seen) out += "--tamper-cutoff=n2"
        out.sorted()
    }
    fun encode(line: String): String = PREFIX + Base64.encode(parse(line).getOrThrow().joinToString(" ").toByteArray(Charsets.UTF_8))
        .replace('+', '-').replace('/', '_').trimEnd('=')
    fun decode(id: String?): List<String>? {
        if (id == null || !id.startsWith(PREFIX) || id.length > 1100) return null
        val raw = Base64.decodeToString(id.removePrefix(PREFIX)) ?: return null
        val args = parse(raw).getOrNull() ?: return null
        return args.takeIf { encode(raw) == id }
    }
    fun line(id: String): String? = decode(id)?.joinToString(" ")
}
