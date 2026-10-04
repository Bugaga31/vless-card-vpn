package com.vlesscardvpn.domain

/**
 * User-entered ByeDPI strategy (ByeByeDPI-style command line) and fake-SNI masking domains.
 * Arguments are passed to the pinned native binary as an argv list (no shell). Only desync
 * options are accepted: listen address/port, files, daemon/pidfile and debug stay app-controlled.
 */
object ByeDpiArgs {
    const val DEFAULT_MASK = "ya.ru"
    const val SNI_PLACEHOLDER = "{sni}"
    /** Popular Russian domains used by competitors as fake ClientHello SNI ("маскировка"). */
    val MASK_DOMAINS = listOf("ya.ru", "yandex.ru", "vk.com", "gosuslugi.ru", "max.ru", "mail.ru",
        "ozon.ru", "wildberries.ru", "avito.ru", "rutube.ru", "sberbank.ru", "dzen.ru")
    /** Ready ByeByeDPI-style strategies the user can insert into the editor and edit. */
    val EXAMPLES = listOf(
        "-d1 -f-1 -t8 -n {sni} -Qo",
        "-f-1 -t8 -n {sni} -Qr,o",
        "-o1 -At,r,s -d1",
        "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s",
        "-q1 -r1+s",
        "-d1 -At,r,s -f-1 -t8 -n {sni} -Qr,o"
    )
    private val maskRegex = Regex("^[a-z0-9?#*](?:[a-z0-9?#*-]{0,61}[a-z0-9?#*])?(?:\\.[a-z0-9?#*](?:[a-z0-9?#*-]{0,61}[a-z0-9?#*])?)+$")
    fun maskDomain(value: String?): String {
        val v = value?.trim()?.lowercase().orEmpty()
        return if (v.length in 3..253 && maskRegex.matches(v)) v else DEFAULT_MASK
    }

    // long name -> (short letter, takes value)
    private val allowed: Map<String, Pair<Char, Boolean>> = mapOf(
        "split" to ('s' to true), "disorder" to ('d' to true), "oob" to ('o' to true), "disoob" to ('q' to true),
        "fake" to ('f' to true), "md5sig" to ('S' to false), "fake-sni" to ('n' to true), "ttl" to ('t' to true),
        "fake-offset" to ('O' to true), "fake-tls-mod" to ('Q' to true), "fake-data" to ('l' to true),
        "oob-data" to ('e' to true), "mod-http" to ('M' to true), "tlsrec" to ('r' to true),
        "tlsminor" to ('m' to true), "udp-fake" to ('a' to true), "def-ttl" to ('g' to true),
        "drop-sack" to ('Y' to false), "auto" to ('A' to true), "auto-mode" to ('L' to true),
        "cache-ttl" to ('u' to true), "timeout" to ('T' to true), "proto" to ('K' to true),
        "hosts" to ('H' to true), "ipset" to ('j' to true), "pf" to ('V' to true), "round" to ('R' to true),
        "tfo" to ('F' to false), "no-udp" to ('U' to false), "no-domain" to ('N' to false),
        "buf-size" to ('b' to true), "wait-send" to ('Z' to false)
    )
    private val byShort = allowed.entries.associate { it.value.first to it.key }
    /** Options that in ByeDPI may load a file: only the inline ":value" form is accepted. */
    private val inlineOnly = setOf("hosts", "ipset", "fake-data")
    const val MAX_LENGTH = 1024

    fun tokenize(line: String): List<String> {
        val out = mutableListOf<String>(); val cur = StringBuilder(); var quote: Char? = null; var has = false
        for (ch in line) {
            when {
                quote != null -> if (ch == quote) quote = null else cur.append(ch)
                ch == '"' || ch == '\'' -> { quote = ch; has = true }
                ch.isWhitespace() -> { if (has || cur.isNotEmpty()) out += cur.toString(); cur.clear(); has = false }
                else -> cur.append(ch)
            }
        }
        require(quote == null) { "Незакрытая кавычка" }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Returns normalized long-form argv (e.g. "--disorder", "1"), or failure with a Russian message. */
    fun parse(line: String, mask: String = DEFAULT_MASK): Result<List<String>> = runCatching {
        require(line.length <= MAX_LENGTH) { "Слишком длинная строка" }
        val domain = maskDomain(mask)
        val tokens = tokenize(line.trim().removePrefix("ciadpi").removePrefix("byedpi").trim())
        require(tokens.isNotEmpty()) { "Пустая стратегия" }
        val out = mutableListOf<String>(); var i = 0
        fun value(name: String, raw: String): String {
            val v = raw.replace(SNI_PLACEHOLDER, domain)
            require(v.isNotEmpty() && v.length <= 256 && v.none { it.isISOControl() }) { "Неверное значение для --$name" }
            if (name in inlineOnly) require(v.startsWith(":")) { "--$name: файлы запрещены, используйте :значение" }
            return v
        }
        fun add(name: String, inline: String?) {
            val (_, takes) = allowed[name] ?: throw IllegalArgumentException("Опция --$name не разрешена")
            if (!takes) { require(inline == null) { "--$name без значения" }; out += "--$name"; return }
            val raw = inline ?: tokens.getOrNull(++i) ?: throw IllegalArgumentException("Нет значения для --$name")
            out += "--$name"; out += value(name, raw)
        }
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.startsWith("--") -> {
                    val body = t.removePrefix("--")
                    add(body.substringBefore('='), if ('=' in body) body.substringAfter('=') else null)
                }
                t.startsWith("-") && t.length >= 2 -> {
                    var j = 1
                    while (j < t.length) {
                        val name = byShort[t[j]] ?: throw IllegalArgumentException("Опция -${t[j]} не разрешена")
                        if (allowed.getValue(name).second) {
                            add(name, t.substring(j + 1).ifEmpty { null }); break
                        } else { add(name, null); j++ }
                    }
                }
                else -> throw IllegalArgumentException("Неожиданный аргумент «$t»")
            }
            i++
        }
        require(out.any { it in setOf("--split", "--disorder", "--oob", "--disoob", "--fake", "--tlsrec", "--mod-http", "--tlsminor", "--udp-fake") }) {
            "Нет ни одного приёма обхода (-s/-d/-o/-q/-f/-r)"
        }
        out
    }

    /** Accepted but risky options, shown under the editor. */
    fun warnings(args: List<String>): List<String> = buildList {
        if ("--md5sig" in args) add("-S (md5sig) не поддерживается ядром многих Android-телефонов: соединения могут не открываться")
        if ("--fake" in args && "--ttl" !in args && "--md5sig" !in args) add("Фейк без -t: используется TTL 8")
        if ("--fake-sni" in args && args.zipWithNext().none { (k, v) -> k == "--fake-tls-mod" && 'o' in v })
            add("Без -Qo фейк-SNI может не подставиться в короткий ClientHello")
    }

    fun loopbackPrefix(port: Int): List<String> {
        require(port in 1024..65535)
        return listOf("--ip", "127.0.0.1", "--port", port.toString(), "--max-conn", "128", "--timeout", "4")
    }
}
