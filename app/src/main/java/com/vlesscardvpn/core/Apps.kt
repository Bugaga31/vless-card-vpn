package com.vlesscardvpn.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Installed apps for split tunneling, and the launcher-icon disguise. */
object Apps {
    data class App(val pkg: String, val label: String)

    fun launcherApps(context: Context): List<App> {
        val pm = context.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(i, 0).map { App(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    /**
     * Russian apps that check for VPN / foreign IP (banks, Gosuslugi, marketplaces, VK, Yandex, operators):
     * excluded from the VPN they see a normal Russian connection and don't block or flag the account.
     */
    val RU_SENSITIVE = listOf(
        "ru.sberbankmobile", "ru.sberbank.sberbankid", "com.idamob.tinkoff.android", "ru.tinkoff.investing", "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android", "ru.raiffeisennews", "ru.gazprombank.android.mobilebank.app", "ru.psbank.mobile", "ru.ftc.faktura.sovkombank",
        "ru.pochta.bank", "com.openbank", "ru.rosbank.android", "ru.mts.money", "ru.nspk.mirpay", "ru.ozon.fintech.finance",
        "ru.rostel", "ru.gosuslugi.goskey", "ru.fns.lkfl", "ru.mos.app", "ru.mos.polls",
        "ru.ozon.app.android", "com.wildberries.ru", "com.avito.android", "ru.yandex.market", "ru.megamarket.marketplace",
        "com.vkontakte.android", "ru.oneme.app", "ru.mail.mailapp", "ru.ok.android", "ru.rutube.app", "ru.vk.store",
        "ru.yandex.searchplugin", "com.yandex.browser", "ru.yandex.taxi", "ru.yandex.yandexmaps", "ru.yandex.music", "ru.kinopoisk",
        "ru.beeline.services", "ru.mts.mymts", "ru.megafon.mlk", "ru.tele2.mytele2", "ru.yota.android",
        "ru.dublgis.dgismobile", "ru.hh.android", "ru.cian.main", "ru.foodfox.client", "com.deliveryclub",
    )

    // ---- launcher icon disguise (activity-aliases in the manifest)
    data class Disguise(val id: String, val alias: String, val title: String)
    val DISGUISES = listOf(
        Disguise("", ".LauncherDefault", "VLESS Card"),
        Disguise("calc", ".LauncherCalc", "Калькулятор"),
        Disguise("notes", ".LauncherNotes", "Заметки"),
        Disguise("weather", ".LauncherWeather", "Погода"),
    )

    fun applyDisguise(context: Context, id: String) {
        val pm = context.packageManager
        val target = DISGUISES.firstOrNull { it.id == id } ?: DISGUISES[0]
        // Enable the new one first so the app never has zero launcher entries.
        DISGUISES.sortedBy { if (it == target) 0 else 1 }.forEach { d ->
            val state = if (d == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching { pm.setComponentEnabledSetting(ComponentName(context.packageName, "com.vlesscardvpn" + d.alias), state, PackageManager.DONT_KILL_APP) }
        }
    }
}
