package com.vlesscardvpn.core

import kotlin.random.Random

/**
 * Advanced DPI Evasion & Whitelist Masquerade Engine.
 * Implements 2025/2026 TSPU bypass techniques:
 * 1. Russian Whitelist SNI pools (Yandex, VK, Gosuslugi, Sber, T-Bank, Ozon, Mail.ru)
 * 2. Fake SNI Pre-flight probing
 * 3. TLS ClientHello fragmentation parameters
 * 4. Fake HTTP Host headers
 */
object AntiDpiEvasionEngine {

    // Tier-1: Ultra-high trust Russian Infrastructure Domains
    val RUSSIAN_WHITELIST_DOMAINS = listOf(
        "yandex.ru",
        "ya.ru",
        "vk.com",
        "vkvideo.ru",
        "gosuslugi.ru",
        "sberbank.ru",
        "tbank.ru",
        "ozon.ru",
        "wildberries.ru",
        "mail.ru",
        "rutube.ru",
        "kinopoisk.ru",
        "dzen.ru",
        "avito.ru",
        "2gis.ru",
        "hh.ru",
        "cian.ru",
        "rambler.ru",
        "rbc.ru",
        "habr.com",
        "mts.ru",
        "megafon.ru",
        "beeline.ru",
        "tele2.ru"
    )

    // Recommended TLS fragmentation presets for TSPU evasion
    enum class FragmentationMode(val packets: String, val interval: String, val description: String) {
        TLS_HELLO("tlshello", "10-20ms", "Разбиение только пакета TLS ClientHello (Оптимально против ТСПУ)"),
        AGGRESSIVE("1-3", "5-15ms", "Фрагментация первых 3 пакетов сессии"),
        DEEP("1-5", "20-40ms", "Глубокая фрагментация для жестких белых списков"),
        OFF("none", "0ms", "Выключено")
    }

    /**
     * Selects optimal SNI for Russian DPI masquerade.
     */
    fun selectStealthSni(operatorHint: String? = null): String {
        return when (operatorHint?.lowercase()) {
            "mts" -> "mts.ru"
            "beeline" -> "beeline.ru"
            "megafon" -> "megafon.ru"
            "tele2", "t2" -> "tele2.ru"
            else -> RUSSIAN_WHITELIST_DOMAINS[Random.nextInt(RUSSIAN_WHITELIST_DOMAINS.size)]
        }
    }

    /**
     * Generates fake HTTP Header injection for TCP transports.
     */
    fun generateHttpFakeHeaders(host: String = "yandex.ru"): Map<String, String> {
        return mapOf(
            "Host" to host,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive"
        )
    }
}
