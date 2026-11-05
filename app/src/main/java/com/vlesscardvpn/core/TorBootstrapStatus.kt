package com.vlesscardvpn.core

import com.vlesscardvpn.tor.TorBootstrap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Start progress of the app's own Tor daemon for the Home card and the route check line.
 * Presentation only: it never marks a route as working (the circuit check does that) and a late sample from an
 * earlier daemon start is rejected by the ticket.
 */
object TorBootstrapStatus {
    enum class Hint { NONE, REFRESHING, RESTARTED, TRY_OTHER }
    data class Info(val active: Boolean = false, val progress: Int = 0, val tag: String = "", val reason: String = "",
                    val stalled: Boolean = false, val hint: Hint = Hint.NONE, val bridge: String = "",
                    /** At least one answer arrived: a reused, already running daemon never flashes «0%». */
                    val sampled: Boolean = false) {
        val done: Boolean get() = active && progress >= 100
        /** Worth a progress line on screen. */
        val starting: Boolean get() = active && sampled && !done
    }
    private var epoch = 0L
    private val mutable = MutableStateFlow(Info())
    val state = mutable.asStateFlow()

    @Synchronized fun begin(bridge: String): Long { epoch++; mutable.value = Info(active = true, bridge = bridge.take(16)); return epoch }
    @Synchronized fun clear() { epoch++; mutable.value = Info() }
    /** [phase] null = no fresh sample (control port busy or daemon restarting): the last progress stays on screen. */
    @Synchronized fun publish(ticket: Long, phase: TorBootstrap.Phase?, stalled: Boolean, hint: Hint = Hint.NONE): Boolean {
        val cur = mutable.value
        if (ticket != epoch || !cur.active) return false
        val done = phase?.done == true
        mutable.value = cur.copy(
            progress = phase?.progress ?: cur.progress,
            tag = phase?.tag ?: cur.tag,
            reason = if (phase == null) cur.reason else if (phase.warning) phase.reason else "",
            stalled = stalled && !done,
            hint = if (done) Hint.NONE else hint,
            sampled = cur.sampled || phase != null || stalled || hint != Hint.NONE)
        return true
    }

    /** A daemon that is visibly moving forward keeps the route «pending» instead of «failed», at most [maxMs]. */
    fun progressing(info: Info, elapsedMs: Long, maxMs: Long = PENDING_MAX_MS): Boolean =
        info.starting && !info.stalled && info.progress > 0 && elapsedMs in 0..maxMs

    const val PENDING_MAX_MS = 180_000L

    /** «Tor подключается: 45% · Загрузка описаний узлов». */
    fun label(info: Info): String = when {
        !info.active || !info.sampled -> ""
        info.done -> "Tor подключён · цепочка готова"
        else -> "Tor подключается: ${info.progress}% · ${TorBootstrap.label(info.tag)}"
    }

    /** Second line: why it waits and what the app does about it. Empty while the start runs normally. */
    fun detail(info: Info): String {
        if (!info.starting) return ""
        val why = TorBootstrap.reason(info.reason).takeIf { it.isNotEmpty() }?.let { it.replaceFirstChar(Char::uppercaseChar) + ". " }.orEmpty()
        val other = if (info.bridge == "obfs4") "WebTunnel" else "obfs4"
        return when (info.hint) {
            Hint.REFRESHING -> why + "Загружаю свежий список мостов…"
            Hint.RESTARTED -> why + "Tor перезапущен со свежими мостами."
            Hint.TRY_OTHER -> why.ifEmpty { "Запуск Tor не продвигается. " } + "Попробуйте транспорт $other в настройках Tor или обновите мосты."
            Hint.NONE -> if (info.stalled) why + "Запуск идёт дольше обычного." else why.trim()
        }
    }

    /** Short suffix for the route check line: « (45% · Загрузка описаний узлов)». */
    fun suffix(info: Info): String = if (!info.starting) "" else " (${info.progress}% · ${TorBootstrap.label(info.tag)})"
}
