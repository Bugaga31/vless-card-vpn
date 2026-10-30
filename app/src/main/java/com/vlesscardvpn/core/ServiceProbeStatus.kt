package com.vlesscardvpn.core

/** Selection, a failed check and an attempted-but-inconclusive check are different states. */
object ServiceProbeStatus {
    fun label(required:Boolean,value:Boolean?,note:String?):String = when {
        !required -> "не выбрано"
        value==true -> "✓"
        value==false -> "проверка не прошла"
        note=="YT_NO_DIRECT_MEDIA" -> "проверка выполнена: прямая ссылка на видео не получена (формат, согласие или ограничения YouTube)"
        note=="YT_HTTP" -> "проверка выполнена: YouTube не выдал видео/страницу"
        note=="YT_FORMAT" -> "проверка выполнена: ответ не является видеоданными"
        note=="TIMEOUT" -> "время проверки истекло — подтверждение не получено"
        note=="CANCELLED" -> "отменена"
        note=="ERROR" -> "ошибка выполнения проверки"
        else -> "проверка запускалась, подтверждение не получено"
    }
}
