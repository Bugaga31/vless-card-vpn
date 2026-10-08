package com.vlesscardvpn.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Masking beyond packets («маскировка поведения и устройства»):
 *  - Russian apps (banks, Госуслуги, маркетплейсы, операторы) bypass the VPN: they see a normal Russian user and don't
 *    break / complain about a VPN;
 *  - a random TUN address on every connect: no fixed "10.10.14.1"-style address that VPN detectors look for;
 *  - cover traffic: now and then the phone opens an ordinary Russian site directly, so the provider sees a usual mix of
 *    browsing next to the encrypted stream instead of one lonely flow to a foreign IP.
 */
object Disguise {
    /** Popular Russian apps that check for a VPN or need a Russian IP. Not installed ones are skipped. */
    val RU_APPS = listOf(
        "ru.sberbankmobile", "ru.sberbank.sberbankid", "com.idamob.tinkoff.android", "ru.tinkoff.investing", "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android", "ru.raiffeisennews", "ru.rosbank.android", "ru.gazprombank.android.mobilebank.app", "ru.psbank.mobile",
        "ru.mkb.mobile", "ru.sovcombank.halvacard", "ru.ozon.fintech.finance", "ru.nspk.mirpay", "ru.rostel", "ru.gosuslugi.goskey",
        "ru.mos.app", "ru.fns.lkfl", "ru.pochta.app", "ru.ozon.app.android", "com.wildberries.ru", "com.avito.android",
        "ru.yandex.taxi", "ru.yandex.market", "ru.yandex.searchplugin", "ru.yandex.yandexmaps", "ru.yandex.disk", "ru.yandex.mail",
        "ru.yandex.music", "com.yandex.browser", "ru.yandex.eda", "ru.yandex.lavka", "ru.kinopoisk", "com.vkontakte.android", "ru.ok.android",
        "ru.vk.store", "ru.oneme.app", "ru.mail.mailapp", "ru.mts.mymts", "ru.beeline.services", "ru.megafon.mlk", "ru.tele2.mytele2",
        "ru.dublgis.dgismobile", "ru.rutube.app", "ru.ivi.client", "ru.hh.android", "ru.cian.main", "ru.samokat.app", "ru.lenta.lentochka",
        "ru.perekrestok.app", "ru.magnit.express.android", "ru.vkusvill", "ru.rzd.pass", "ru.aeroflot",
    )

    private val rnd = SecureRandom()

    /** Random private TUN address (IPv4 /30 network, its .1 host) and IPv6 ULA, different on every connect. */
    fun tunV4(): String = "10.${1 + rnd.nextInt(254)}.${rnd.nextInt(256)}.${4 * rnd.nextInt(64) + 1}"
    fun tunV6(): String = "fd%02x:%04x:%04x::1".format(rnd.nextInt(256), rnd.nextInt(65536), rnd.nextInt(65536))

    val COVER_SITES = listOf("https://ya.ru/", "https://dzen.ru/", "https://vk.com/", "https://www.ozon.ru/", "https://mail.ru/",
        "https://www.avito.ru/", "https://www.gosuslugi.ru/", "https://www.wildberries.ru/", "https://www.kinopoisk.ru/", "https://www.rbc.ru/")
    /** Seconds until the next cover request: irregular like a person, 40 s…3 min. */
    fun nextDelaySec(): Int = 40 + rnd.nextInt(140)
    fun pick(): String = COVER_SITES[rnd.nextInt(COVER_SITES.size)]

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }
    /** One cover request (the app itself is outside the VPN, so this goes directly). Reads ≤ 32 KB. */
    fun cover() = runCatching {
        client.newCall(Request.Builder().url(pick()).header("User-Agent", Tester.UA).header("Accept-Language", "ru-RU,ru;q=0.9").build()).execute().use { r ->
            val src = r.body?.byteStream() ?: return@use; val buf = ByteArray(8192); var n = 0
            while (n < 32_768) { val k = src.read(buf); if (k < 0) break; n += k }
        }
    }
}
