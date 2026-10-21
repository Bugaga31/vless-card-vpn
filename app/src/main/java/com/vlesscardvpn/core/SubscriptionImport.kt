package com.vlesscardvpn.core

import com.vlesscardvpn.model.Base64

/** Fixed diagnostic codes only; never persist credentials, raw links or server error bodies. */
object SubscriptionImport {
    fun failure(body: String): String {
        val plain=body.trim();val decoded=Base64.decodeToString(plain.replace("\n","").replace("\r",""))
        val text=if(plain.contains("://")) plain else decoded ?: plain
        return if(Regex("(?i)\\bmieru://").containsMatchIn(text)) "unsupported-mieru" else "unsupported-or-invalid"
    }
    fun label(code: String): String = when(code) {
        "unsupported-mieru" -> "Mieru: текущий Xray-клиент не поддерживает этот формат; рабочие конфигурации не импортированы"
        "unsupported-or-invalid" -> "нет поддерживаемых конфигураций или повреждённый формат; прежний список сохранён"
        "download" -> "не удалось загрузить источник; прежний список сохранён"
        else -> ""
    }
}
