package com.vlesscardvpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit

/** Network samples for connection guidance. User traffic and every check stay on the active VLESS route. */
object Whitelist {
    enum class Verdict(val title: String) {
        WHITELIST("Маршрут через VLESS доступен частично"),
        NORMAL("Маршрут через VLESS отвечает"),
        OPEN("Через VLESS доступны все проверенные группы сайтов"),
        NONE("Маршрут через VLESS недоступен"),
    }
    val WHITE = listOf("https://ya.ru", "https://vk.com", "https://www.gosuslugi.ru", "https://mail.ru")
    val NORMAL = listOf("https://habr.com", "https://www.wikipedia.org", "https://www.google.com", "https://4pda.to")
    val BLOCKED = listOf("https://www.instagram.com", "https://x.com", "https://rutracker.org")

    data class Result(val white: Int, val normal: Int, val blocked: Int, val verdict: Verdict, val at: Long = System.currentTimeMillis(), val viaServer: Boolean = false)

    private fun most(ok: Int, of: Int) = ok * 2 > of

    fun verdict(white: Int, normal: Int, blocked: Int): Verdict = when {
        white + normal + blocked == 0 -> Verdict.NONE
        white == WHITE.size && normal == NORMAL.size && blocked == BLOCKED.size -> Verdict.OPEN
        white + normal + blocked >= 2 -> Verdict.NORMAL
        else -> Verdict.WHITELIST
    }

    val last = MutableStateFlow<Result?>(null)

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val port = Tunnel.socks?.port
        if (port == null) return@withContext Result(0, 0, 0, Verdict.NONE).also { last.value = it }
        coroutineScope {
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            val all = (WHITE + NORMAL + BLOCKED).map { url -> async {
                gate.withPermit { url to Tester.site(url, port).answers }
            } }.awaitAll().toMap()
            val w = WHITE.count { all[it] == true }; val n = NORMAL.count { all[it] == true }; val b = BLOCKED.count { all[it] == true }
            Result(w, n, b, verdict(w, n, b), viaServer = true).also { last.value = it }
        }
    }

    fun report(r: Result): String {
        if (!r.viaServer) return "Прямая проверка отключена. Подключитесь через VLESS, чтобы проверять сайты по защищённому маршруту."
        return "Проверено через выбранный VLESS-сервер: доступные сайты — разрешённые ${r.white}/${WHITE.size}, обычные ${r.normal}/${NORMAL.size}, ограниченные ${r.blocked}/${BLOCKED.size}. Эта проверка не определяет ограничения самой сети."
    }
}
