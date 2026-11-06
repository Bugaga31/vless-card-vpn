package com.vlesscardvpn.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.DateFormat
import java.util.Date

/** Local, opt-in-to-view system hint: no traces, exception messages or identifiers are collected or sent. */
object AppExitHint {
    fun label(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Android сообщил о нехватке памяти"
        ApplicationExitInfo.REASON_CRASH -> "Android сообщил о сбое приложения"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Android сообщил о сбое нативного компонента"
        ApplicationExitInfo.REASON_ANR -> "Android сообщил, что приложение не отвечало"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android остановил приложение из-за нагрузки"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Android сообщил об ошибке запуска"
        else -> null
    }

    fun last(context: Context, now: Long = System.currentTimeMillis()): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return@runCatching null
            val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .firstOrNull { it.processName == context.packageName && it.timestamp in (now - 7 * 86_400_000L)..now }
                ?: return@runCatching null
            val description = label(exit.reason) ?: return@runCatching null
            "$description · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(exit.timestamp))}"
        }.getOrNull()
    }
}
