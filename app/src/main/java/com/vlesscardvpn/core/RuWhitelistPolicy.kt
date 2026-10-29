package com.vlesscardvpn.core

import com.vlesscardvpn.model.Settings

/** Curated service suffixes, NOT all .ru or geoip:ru. Shared generic CDNs are not bypassed. */
object RuWhitelistPolicy {
    const val DNS = "77.88.8.8"
    val SERVICES = listOf("gosuslugi.ru", "sberbank.ru", "sber.ru", "tbank.ru", "tinkoff.ru",
        "alfabank.ru", "vtb.ru", "nspk.ru", "yandex.ru", "ya.ru", "yandex.net", "yastatic.net",
        "vk.com", "vk.ru", "vkvideo.ru", "userapi.com", "ok.ru", "mail.ru", "ozon.ru",
        "ozonusercontent.com", "wildberries.ru", "wb.ru", "wbstatic.net", "avito.ru", "rzd.ru", "pochta.ru")
    fun domains(settings: Settings): List<String> {
        if (!settings.ruWhitelist || settings.torAll) return emptyList()
        // A force-VPN child also excludes its parent suffix from direct DNS/routing.
        val vpn = settings.alwaysVpn.map { it.trim().trimEnd('.').lowercase().removePrefix("domain:") }
        return SERVICES.filter { d -> vpn.none { v -> v == d || v.endsWith(".$d") || d.endsWith(".$v") } }
    }
    fun rules(settings: Settings) = (domains(settings).map { "domain:$it" } + RuGeoPolicy.domains(settings)).distinct()
}
