package com.vlesscardvpn.tor

/**
 * Tor start progress read from the ControlPort (`GETINFO status/bootstrap-phase`).
 * Only whitelisted fields leave this parser: the phase TAG and the failure REASON are mapped to fixed Russian
 * texts. Tor's own SUMMARY/WARNING strings and HOSTADDR/HOSTID (the bridge address) never reach the screen.
 */
object TorBootstrap {
    data class Phase(
        val progress: Int,
        val tag: String,
        /** Tor marked the phase WARN (a bridge connection failed); the start may still continue. */
        val warning: Boolean = false,
        val reason: String = "",
        /** How many times this problem happened (Tor's COUNT). */
        val count: Int = 0,
        /** Tor itself recommends warning the user (RECOMMENDATION=warn). */
        val recommendWarn: Boolean = false,
    ) { val done: Boolean get() = progress >= 100 }

    enum class Verdict { STARTING, PROGRESS, STALLED, DONE }

    private val tagShape = Regex("[a-z_]{1,32}")
    private val reasonShape = Regex("[A-Z_]{1,32}")

    fun parse(reply: String): Phase? {
        val line = reply.lineSequence().map(String::trim).firstOrNull { "status/bootstrap-phase=" in it } ?: return null
        val body = line.substringAfter("status/bootstrap-phase=")
        val severity = body.substringBefore(' ')
        val fields = keywords(body.substringAfter(' ', ""))
        val progress = fields["PROGRESS"]?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val tag = fields["TAG"]?.takeIf(tagShape::matches).orEmpty()
        val warn = severity == "WARN"
        return Phase(progress, tag, warn,
            reason = if (warn) fields["REASON"]?.takeIf(reasonShape::matches).orEmpty() else "",
            count = if (warn) fields["COUNT"]?.toIntOrNull()?.coerceIn(0, 100_000) ?: 0 else 0,
            recommendWarn = warn && fields["RECOMMENDATION"] == "warn")
    }

    /** KEY=VALUE and KEY="quoted \"value\"" pairs of a status event. */
    internal fun keywords(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i] == ' ') i++
            val eq = text.indexOf('=', i)
            if (eq <= i) break
            val key = text.substring(i, eq)
            if (' ' in key) { i = text.indexOf(' ', i).let { if (it < 0) text.length else it }; continue }
            i = eq + 1
            val value = StringBuilder()
            if (i < text.length && text[i] == '"') {
                i++
                while (i < text.length && text[i] != '"') {
                    if (text[i] == '\\' && i + 1 < text.length) i++
                    value.append(text[i]); i++
                }
                i++ // closing quote
            } else {
                while (i < text.length && text[i] != ' ') { value.append(text[i]); i++ }
            }
            if (key.length <= 32 && key !in out) out[key] = value.toString()
        }
        return out
    }

    /** Fixed Russian name of a bootstrap phase (tor 0.4.x tags). */
    fun label(tag: String): String = when (tag) {
        "starting" -> "Запуск"
        "conn_pt", "conn_done_pt" -> "Запуск транспорта мостов"
        "conn_proxy", "conn_done_proxy" -> "Подключение через VLESS"
        "conn", "conn_done" -> "Подключение к мосту"
        "handshake", "handshake_done" -> "Рукопожатие с мостом"
        "onehop_create", "requesting_status", "loading_status" -> "Загрузка консенсуса сети"
        "loading_keys" -> "Загрузка ключей узлов"
        "requesting_descriptors", "loading_descriptors" -> "Загрузка описаний узлов"
        "enough_dirinfo" -> "Каталог узлов загружен"
        "ap_conn_pt", "ap_conn_done_pt", "ap_conn_proxy", "ap_conn_done_proxy", "ap_conn", "ap_conn_done",
        "ap_handshake", "ap_handshake_done" -> "Подключение для цепочки"
        "circuit_create" -> "Строю цепочку"
        "done" -> "Готово"
        else -> "Подключение"
    }

    /** Fixed text for Tor's OR-connection failure reason; arbitrary daemon text is never shown. */
    fun reason(code: String): String = when (code) {
        "TIMEOUT" -> "мосты не отвечают"
        "CONNECTREFUSED" -> "мост отклонил подключение"
        "CONNECTRESET" -> "подключение к мосту сброшено"
        "NOROUTE" -> "нет маршрута до моста"
        "IDENTITY" -> "отпечаток моста не совпал"
        "IOERROR" -> "ошибка связи с мостом"
        "PT_MISSING" -> "транспорт мостов не запустился"
        "RESOURCELIMIT" -> "не хватает ресурсов телефона"
        "" -> ""
        else -> "мост недоступен"
    }

    /**
     * One daemon start: STALLED when progress has not grown for [stallMs] (silent or blocked bridges).
     * Pure and clock-injected; a restarted daemon needs a new [Watch].
     */
    class Watch(private val stallMs: Long = STALL_MS) {
        private var best = -1
        private var since = Long.MIN_VALUE
        private var started = Long.MIN_VALUE
        fun observe(phase: Phase?, now: Long): Verdict {
            if (started == Long.MIN_VALUE) started = now
            if (phase != null && phase.done) return Verdict.DONE
            if (phase != null && phase.progress > best) { best = phase.progress; since = now }
            val quietFrom = if (since == Long.MIN_VALUE) started else since
            return when {
                now < quietFrom -> Verdict.STARTING // clock went back: never claim a stall
                now - quietFrom >= stallMs -> Verdict.STALLED
                best < 0 -> Verdict.STARTING
                else -> Verdict.PROGRESS
            }
        }
        val progress: Int get() = best.coerceAtLeast(0)
    }

    const val STALL_MS = 60_000L
    /** Polling stops after this even without DONE: the circuit check keeps reporting the route state. */
    const val WATCH_MAX_MS = 10 * 60_000L
}
