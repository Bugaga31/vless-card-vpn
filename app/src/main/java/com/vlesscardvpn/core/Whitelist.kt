package com.vlesscardvpn.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * «Белые списки?» (idea from the open project WhiteListCheck): three groups of sites are opened directly (the app
 * itself is outside the VPN) — whitelisted Russian sites, ordinary foreign sites, blocked sites. Majority per group:
 * white yes / ordinary no → the operator's whitelist mode is on, and only servers that hide behind allowed
 * sites (SNI masks of vk.com / ya.ru, CDN / Yandex Cloud fronting) can work.
 */
object Whitelist {
    enum class Verdict(val title: String) {
        WHITELIST("Включены белые списки: открываются только разрешённые сайты"),
        NORMAL("Интернет обычный, белых списков нет"),
        OPEN("Открывается даже заблокированное — вы не в РФ или трафик уже идёт через VPN"),
        NONE("Интернета нет совсем"),
    }
    val WHITE = listOf("https://ya.ru", "https://vk.com", "https://www.gosuslugi.ru", "https://mail.ru")
    val NORMAL = listOf("https://habr.com", "https://www.wikipedia.org", "https://www.google.com", "https://4pda.to")
    val BLOCKED = listOf("https://www.instagram.com", "https://x.com", "https://rutracker.org")

    data class Result(val white: Int, val normal: Int, val blocked: Int, val verdict: Verdict, val at: Long = System.currentTimeMillis())

    private fun most(ok: Int, of: Int) = ok * 2 > of

    fun verdict(white: Int, normal: Int, blocked: Int): Verdict = when {
        most(blocked, BLOCKED.size) -> Verdict.OPEN
        most(normal, NORMAL.size) -> Verdict.NORMAL
        most(white, WHITE.size) -> Verdict.WHITELIST
        white + normal + blocked == 0 -> Verdict.NONE
        else -> if (white > normal) Verdict.WHITELIST else Verdict.NONE
    }

    val last = MutableStateFlow<Result?>(null)

    /** Any HTTP answer (even 403) = the site is reachable: TCP + TLS passed. */
    private fun reachable(url: String): Boolean = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.requestMethod = "HEAD"; c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
        try { c.responseCode > 0 } finally { c.disconnect() }
    }.getOrDefault(false)

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        coroutineScope {
            val all = (WHITE + NORMAL + BLOCKED).map { u -> async { u to reachable(u) } }.awaitAll().toMap()
            val w = WHITE.count { all[it] == true }; val n = NORMAL.count { all[it] == true }; val b = BLOCKED.count { all[it] == true }
            Result(w, n, b, verdict(w, n, b)).also { last.value = it }
        }
    }

    fun report(r: Result): String = r.verdict.title + " (разрешённые ${r.white}/${WHITE.size}, обычные ${r.normal}/${NORMAL.size}, заблокированные ${r.blocked}/${BLOCKED.size})." +
        when (r.verdict) {
            Verdict.WHITELIST -> "\nЧто поможет: серверы, спрятанные за разрешёнными сайтами — маски с SNI vk.com / ya.ru (семейство «Фирменные»), серверы за CDN или Yandex Cloud, WARP через сервер. ByeDPI без сервера тут не поможет. Запускаю подбор маскировки — сначала попробую маски под разрешённые сайты."
            Verdict.NORMAL -> "\nОбычный режим: работают все способы, включая ByeDPI без сервера."
            Verdict.OPEN -> ""
            Verdict.NONE -> "\nПроверьте мобильные данные / Wi-Fi."
        }
}
