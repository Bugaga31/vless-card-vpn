package com.vlesscardvpn.core

import kotlin.random.Random

/**
 * Движок стратегий стелса: сборник техник обхода ТСПУ из открытых
 * проектов (zapret, GoodbyeDPI, byebyedpi) и /g/ PTG-тредов.
 *
 * AUTO_CASCADE — режим «одна кнопка»: стратегии применяются каскадом,
 * от агрессивной к консервативной, пока соединение не пройдёт.
 */
object EvasionStrategies {

    enum class Strategy(
        val id: String,
        val displayName: String,
        val tagLine: String,
        val description: String,
        val recommendedFragmentPackets: String,
        val recommendedFragmentInterval: String,
        val uTlsFingerprint: String,
        val origin: String
    ) {
        /**
         * Каскад «на всё случаи»: пробует стратегии по очереди.
         * Параметры фрагментации берутся с первой стадии каскада.
         */
        AUTO_CASCADE(
            id = "auto_cascade",
            displayName = "🤖 Автокаскад (кнопка «Подключить»)",
            tagLine = "Сам попробует всё по очереди, пока не заработает",
            description = "Каскад: Ghost-сплит → Белый плащ РФ → Фейк-пакеты → Морф-хаос → Reality. " +
                "Если стадия не прошла end-to-end проверку — автоматически следующая.",
            recommendedFragmentPackets = "tlshello",
            recommendedFragmentInterval = "10-20ms",
            uTlsFingerprint = "chrome",
            origin = "AutoPilot"
        ),
        ZAPRET_GHOST(
            id = "zapret_ghost",
            displayName = "👻 Zapret Ghost",
            tagLine = "Агрессивный сплит TLS + защита от ТСПУ",
            description = "Разбиение TLS ClientHello на микросегменты с задержкой 10-20ms. " +
                "Ломает сигнатурные сканеры ТСПУ: длина первого сегмента меньше порога сигнатуры.",
            recommendedFragmentPackets = "tlshello",
            recommendedFragmentInterval = "10-20ms",
            uTlsFingerprint = "chrome",
            origin = "zapret (Flowsead)"
        ),
        WHITE_RU(
            id = "white_ru",
            displayName = "🏛️ Белый плащ РФ",
            tagLine = "Маскировка под Yandex, VK, Госуслуги, Ozon",
            description = "SNI и отпечаток трафика неотличимы от обращений к доверенной " +
                "инфраструктуре РФ. Целевой отпечаток uTLS — Safari (iOS-клиент, как у Яндекса).",
            recommendedFragmentPackets = "1-2",
            recommendedFragmentInterval = "5-15ms",
            uTlsFingerprint = "safari",
            origin = "Whitelist-mimic / community"
        ),
        MORPH_CHAOS(
            id = "morph_chaos",
            displayName = "⚡ Morph Chaos",
            tagLine = "Динамический шум + джиттер + ротация SNI",
            description = "Случайные интервалы фрагментации и ротация SNI из пула доверенных " +
                "доменов на каждом соединении — против эвристического и ML-анализа.",
            recommendedFragmentPackets = "1-3",
            recommendedFragmentInterval = "15-30ms",
            uTlsFingerprint = "randomized",
            origin = "/g/ PTG"
        ),
        TURBO_REALITY(
            id = "turbo_reality",
            displayName = "🚀 Reality XTLS-Vision",
            tagLine = "Максимальная скорость без задержек",
            description = "Чистый VLESS Reality с XTLS Vision flow. Без фрагментации — " +
                "для серверов, где Reality-рукопожатие неотличимо от настоящего TLS к SNI.",
            recommendedFragmentPackets = "none",
            recommendedFragmentInterval = "0ms",
            uTlsFingerprint = "chrome",
            origin = "XTLS community"
        ),
        FAKE_PACKETS(
            id = "fake_packets",
            displayName = "🎭 Fake-Packets First",
            tagLine = "Фейк-пакеты до реального ClientHello",
            description = "Перед настоящим ClientHello отправляются пакеты-приманки " +
                "с «мусорной» сигнатурой: DPI собирает неверную картину рукопожатия, " +
                "реальный пакет проходит как ретрансмиссия.",
            recommendedFragmentPackets = "1-3",
            recommendedFragmentInterval = "1-5ms",
            uTlsFingerprint = "chrome",
            origin = "zapret: fake / fakeholder"
        ),
        QUIC_BLOCK(
            id = "quic_block",
            displayName = "🚫 QUIC Killer (TCP fallback)",
            tagLine = "Блок UDP/443 → браузер падает на TCP",
            description = "QUIC/HTTP3 не фрагментируется стандартными способами. " +
                "Блокируем UDP/443 — клиент принудительно возвращается на TCP/TLS, " +
                "где работает сплит ClientHello (классика GoodbyeDPI).",
            recommendedFragmentPackets = "tlshello",
            recommendedFragmentInterval = "10-20ms",
            uTlsFingerprint = "chrome",
            origin = "GoodbyeDPI (block_QUIC)"
        ),
        HTTP_SPLIT(
            id = "http_split",
            displayName = "✂️ HTTP Host Split",
            tagLine = "Разрез заголовка Host + mixed case",
            description = "Для plain-HTTP: заголовок Host разрезается на части, " +
                "домен в смешанном регистре (YaNdEx.Ru). DPI ищет точное совпадение — не находит.",
            recommendedFragmentPackets = "1-2",
            recommendedFragmentInterval = "5-15ms",
            uTlsFingerprint = "safari",
            origin = "GoodbyeDPI (moderate)"
        ),
        DOH_BOOTSTRAP(
            id = "doh_bootstrap",
            displayName = "🔐 DoH Bootstrap",
            tagLine = "DNS через HTTPS: ноль подмен на резолве",
            description = "Резолв адресов через DNS-over-HTTPS до старта туннеля. " +
                "Исключает отравление кэша DNS и подмену A-записей на стороне провайдера.",
            recommendedFragmentPackets = "tlshello",
            recommendedFragmentInterval = "10-20ms",
            uTlsFingerprint = "chrome",
            origin = "Cloudflare/Google DoH"
        );

        companion object {
            fun fromId(id: String): Strategy {
                return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO_CASCADE
            }
        }
    }

    /**
     * Порядок каскада AUTO_CASCADE: от самой совместимой к самой быстрой.
     * AUTO_CASCADE внутри плана не участвует — это мета-режим.
     */
    fun cascadePlan(): List<Strategy> = listOf(
        Strategy.ZAPRET_GHOST,
        Strategy.WHITE_RU,
        Strategy.FAKE_PACKETS,
        Strategy.MORPH_CHAOS,
        Strategy.TURBO_REALITY
    )

    /**
     * Активная стратегия по текущей настройке fragmentPackets.
     * "auto"/"auto_cascade"/пусто → AUTO_CASCADE.
     * Легаси-спеки ("tlshello", "1-3" и т.п.) трактуются как Ghost —
     * это историческое дефолтное поведение (fingerprint = chrome).
     */
    fun resolveActive(fragmentPacketsSetting: String): Strategy {
        val v = fragmentPacketsSetting.trim()
        if (v.isEmpty() || v.equals("auto", ignoreCase = true) ||
            v.equals("auto_cascade", ignoreCase = true)
        ) return Strategy.AUTO_CASCADE
        return Strategy.entries.firstOrNull { it.id.equals(v, ignoreCase = true) }
            ?: Strategy.ZAPRET_GHOST
    }

    /**
     * Эффективные параметры фрагментации для конфига ядра.
     * - Явный id стратегии ("white_ru", "turbo_reality"…) → параметры стратегии.
     * - "auto"/"auto_cascade"/пусто → первая стадия каскада.
     * - Легаси-спеки ("tlshello", "1-3") → проходят в ядро БЕЗ ИЗМЕНЕНИЙ,
     *   чтобы не ломать пользовательские настройки и старые дефолты.
     * null = фрагментация не нужна (чистый Reality Vision на полной скорости).
     */
    fun effectiveFragmentParams(
        fragmentPacketsSetting: String,
        currentInterval: String
    ): Pair<String, String>? {
        val v = fragmentPacketsSetting.trim()
        if (v.isEmpty() || v.equals("auto", ignoreCase = true) ||
            v.equals("auto_cascade", ignoreCase = true)
        ) {
            val first = cascadePlan().first()
            return first.recommendedFragmentPackets to first.recommendedFragmentInterval
        }
        val strategy = Strategy.entries.firstOrNull { it.id.equals(v, ignoreCase = true) }
            ?: return v to currentInterval // легаси-спека: pass-through
        val (packets, interval) = strategy.recommendedFragmentPackets to strategy.recommendedFragmentInterval
        // "none"/"0ms" = фрагментация не нужна
        if (packets.equals("none", ignoreCase = true) || interval == "0ms") return null
        return packets to interval
    }

    /** Fingerprint uTLS для активной стратегии (если в конфиге сервера не задан свой). */
    fun activeFingerprint(fragmentPacketsSetting: String): String =
        resolveActive(fragmentPacketsSetting).uTlsFingerprint

    /** Человекочитаемый план каскада — для тостов и экрана статуса. */
    fun describePlan(): String =
        cascadePlan().joinToString(" → ") { it.displayName.substringAfter(" ").trim() }

    /**
     * Российские высокодоверенные SNI (белые списки ТСПУ):
     * госсектор, банки, ритейл, операторы.
     */
    val TRUSTED_SNI_LIST = listOf(
        "yandex.ru", "ya.ru", "vk.com", "vkvideo.ru", "gosuslugi.ru",
        "sberbank.ru", "tbank.ru", "ozon.ru", "wildberries.ru", "mail.ru",
        "rutube.ru", "kinopoisk.ru", "dzen.ru", "avito.ru", "2gis.ru",
        "mts.ru", "megafon.ru", "beeline.ru", "tele2.ru", "habr.com"
    )

    fun getRandomTrustedSni(): String = TRUSTED_SNI_LIST[Random.nextInt(TRUSTED_SNI_LIST.size)]
}
