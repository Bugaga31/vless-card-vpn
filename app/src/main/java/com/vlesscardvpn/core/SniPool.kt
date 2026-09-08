package com.vlesscardvpn.core

import kotlin.random.Random

/**
 * Russian SNI pool for rotation-based DPI evasion.
 * When TSPU fingerprints a specific SNI (e.g. yandex.ru), rotating to another
 * popular Russian domain defeats the signature-based blocking.
 *
 * Контракт пула (проверяется тестами):
 *  - только bare-домены БЕЗ путей ("host.tld", никаких "host.tld/path");
 *  - только TLD: .ru / .by / .com;
 *  - высокодоверенные и НЕ заблокированные ТСПУ ресурсы — маскировка
 *    под заблокированный домен бессмысленна.
 */
object SniPool {

    // High-trust Russian/Belarusian domains: popular, CDN-backed, unlikely to be blocked by TSPU
    private val primarySni = listOf(
        // Госсектор и госуслуги
        "gosuslugi.ru", "mos.ru", "spb.ru", "mosreg.ru", "kremlin.ru",
        "government.ru", "duma.gov.ru", "nalog.gov.ru", "cbr.ru",
        "customs.gov.ru", "pfr.gov.ru", "roskazna.gov.ru", "rosreestr.gov.ru",
        "gibdd.ru", "mvd.ru", "mil.ru", "mid.ru", "rkn.gov.ru",
        "sledcom.ru", "digital.gov.ru",
        // Операторы связи
        "mts.ru", "beeline.ru", "megafon.ru", "tele2.ru",
        // Банки и финансы
        "sberbank.ru", "tbank.ru", "vtb.ru", "gazprombank.ru",
        "alfabank.ru", "rosbank.ru", "rshb.ru", "psbank.ru",
        "pochta.ru", "russianpost.ru", "banki.ru", "sravni.ru",
        "finam.ru", "domclick.ru",
        // Ритейл и маркетплейсы
        "ozon.ru", "wildberries.ru", "avito.ru", "dns-shop.ru",
        "mvideo.ru", "eldorado.ru", "citilink.ru", "svyaznoy.ru",
        "sportmaster.ru", "lamoda.ru", "kolesa.ru",
        // Поиск, почта, порталы
        "yandex.ru", "mail.ru", "rambler.ru", "internet.ru", "sputnik.ru",
        // СМИ и медиа
        "ria.ru", "tass.ru", "rbc.ru", "lenta.ru", "kp.ru", "mk.ru",
        "rg.ru", "iz.ru", "gazeta.ru", "kommersant.ru", "vedomosti.ru",
        "1tv.ru", "ntv.ru", "smotrim.ru", "matchtv.ru", "rutube.ru",
        "kinopoisk.ru", "ivi.ru", "wink.ru", "kion.ru", "dzen.ru",
        // Спорт, tech-медиа, блоги
        "sport.ru", "sports.ru", "championat.com", "f1news.ru",
        "topwar.ru", "habr.com", "vc.ru", "pikabu.ru", "livejournal.com",
        // Путешествия и транспорт
        "tutu.ru", "aviasales.ru", "biletix.ru", "travelata.ru",
        "s7.ru", "aeroflot.ru", "utair.ru", "rzd.ru", "uralairlines.ru",
        // Авто, недвижимость, работа, сервисы
        "auto.ru", "drive2.ru", "zr.ru", "cian.ru", "hh.ru", "2gis.ru",
        "the-village.ru", "afisha.ru", "kudago.com", "afisha.mail.ru",
        "auto.mail.ru", "vk.com",
        // Беларусь
        "tut.by", "onliner.by", "kufar.by", "21vek.by", "wildberries.by",
        "oz.by", "e-dostavka.by", "belarusbank.by", "priorbank.by",
        "belta.by", "sb.by", "interfax.by", "belarus.by", "minsk.by",
        "gomel.by", "brest.by", "grodno.by", "mogilev.by", "vitebsk.by",
        "belnovosti.by", "ej.by", "mil.by", "government.by",
        "president.gov.by",
        // Регионы
        "krasnodar.ru", "sochi.ru", "anapa.ru", "novorossiysk.ru"
    )

    // CDN-backed domains that are globally accessible and unlikely blocked.
    // Только bare-домены крупных вендоров — используются как глобальный fallback.
    private val cdnSni = listOf(
        "samsung.com", "microsoft.com", "azure.com", "cloudflare.com",
        "akamai.com", "fastly.com", "cloudfront.net", "googleapis.com",
        "gstatic.com", "apple.com", "icloud.com", "wikipedia.org",
        "wikimedia.org", "mozilla.org", "github.com", "gitlab.com",
        "stackoverflow.com", "medium.com", "twitch.tv", "discord.com",
        "slack.com", "zoom.us", "notion.so", "figma.com", "canva.com",
        "adobe.com", "dropbox.com", "atlassian.com", "oracle.com",
        "ibm.com", "intel.com", "amd.com", "nvidia.com", "cisco.com",
        "vmware.com", "docker.com", "kubernetes.io", "grafana.com",
        "elastic.co", "duckduckgo.com", "protonmail.com", "yandex.com",
        "alibabacloud.com", "baidu.com", "qq.com", "taobao.com",
        "jd.com", "bilibili.com", "weibo.com", "zhihu.com"
    )

    private var currentIndex = Random.nextInt(primarySni.size)

    /**
     * Rotates to the next SNI in the pool and returns it.
     * Thread-safe for concurrent connections.
     */
    @Synchronized
    fun nextSni(): String {
        val sni = primarySni[currentIndex]
        currentIndex = (currentIndex + 1) % primarySni.size
        return sni
    }

    /**
     * Returns a completely random SNI from the pool.
     */
    fun randomSni(): String = primarySni[Random.nextInt(primarySni.size)]

    /**
     * Returns a random CDN-backed SNI for global fallback.
     */
    fun randomCdnSni(): String = cdnSni[Random.nextInt(cdnSni.size)]

    /**
     * Returns the full pool size for UI display.
     */
    fun poolSize(): Int = primarySni.size
}
