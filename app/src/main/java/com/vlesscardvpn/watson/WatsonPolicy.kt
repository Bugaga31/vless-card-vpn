package com.vlesscardvpn.watson

import com.vlesscardvpn.model.Settings

/** Read-only facts, not malware verdicts. No app inventory, URLs or credentials leave the device. */
data class WatsonFinding(val id:String,val title:String,val detail:String)
data class WatsonApp(val packageName:String,val label:String,val requested:Set<String>,val granted:Set<String>,
    val accessibilityDeclared:Boolean=false,val notificationDeclared:Boolean=false,val vpnDeclared:Boolean=false,
    val exportedCount:Int=0,val debuggable:Boolean=false,val error:Boolean=false)
object WatsonPolicy {
    private val sensitive=linkedMapOf(
        "android.permission.RECORD_AUDIO" to "Микрофон", "android.permission.CAMERA" to "Камера",
        "android.permission.READ_SMS" to "Чтение SMS", "android.permission.RECEIVE_SMS" to "Получение SMS",
        "android.permission.READ_CONTACTS" to "Контакты", "android.permission.READ_CALL_LOG" to "Журнал звонков",
        "android.permission.ACCESS_FINE_LOCATION" to "Точное местоположение",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "Местоположение в фоне")
    fun app(a:WatsonApp):List<WatsonFinding> {
        if(a.error) return listOf(WatsonFinding("unreadable","Данные недоступны","Android не позволил прочитать пакет. Это неизвестность, не признак безопасности."))
        val out=mutableListOf<WatsonFinding>()
        sensitive.forEach {(p,name)->if(p in a.requested) out+=WatsonFinding(p,name,
            if(p in a.granted) "Android сообщает: разрешение выдано. Это не доказательство его использования или слежки."
            else "Запрошено в манифесте; Android не сообщает о выдаче. Не означает доступ к данным.")}
        if(a.accessibilityDeclared) out+=WatsonFinding("accessibility","Служба специальных возможностей","Объявлена служба, способная получать широкий доступ при включении пользователем. Включённость здесь не проверена.")
        if(a.notificationDeclared) out+=WatsonFinding("notifications","Служба чтения уведомлений","Объявлена служба доступа к уведомлениям. Наличие службы не означает, что доступ выдан.")
        if(a.vpnDeclared) out+=WatsonFinding("vpn","Служба VPN","Объявлен VPN-сервис. Это нормальная возможность VPN-приложений, не признак заражения.")
        if(a.debuggable) out+=WatsonFinding("debug","Отладочная сборка","Флаг debuggable включён. Для доверенного инструмента разработчика это может быть намеренно; проверьте источник установки.")
        if(a.exportedCount>0) out+=WatsonFinding("exported","Доступные извне компоненты: ${a.exportedCount}","Например, главный экран приложения. Подсчёт не проверяет разрешения компонентов и не доказывает уязвимость.")
        if(out.isEmpty()) out+=WatsonFinding("no_signals","Выбранные признаки не найдены","Это не антивирусный вердикт: код, поведение, подпись и скрытая слежка не проверены.")
        return out
    }
    fun privacy(s:Settings):List<WatsonFinding> = buildList {
        if(!s.torAll && s.ruWhitelist) add(WatsonFinding("ru_list","Включены прямые RU-исключения","Часть сервисных маршрутов и DNS может идти напрямую. Для строгого маршрута отключите RU-исключения и переподключитесь."))
        if(!s.torAll && s.ruAllDirect) add(WatsonFinding("ru_all","RU-трафик направляется напрямую","Это осознанное исключение из VPN, а не весь трафик через туннель."))
        add(WatsonFinding("lockdown","Блокировка без VPN: не проверена","В системных настройках VPN проверьте «Постоянный VPN» и «Блокировать соединения без VPN». Наличие этих настроек зависит от Android."))
        add(WatsonFinding("capture","DNS / IPv6 / смена сети: не подтверждено","Проверка настроек не заменяет измерение утечек на этом телефоне."))
        add(WatsonFinding("metadata","Невидимость провайдеру не гарантируется","IP первого узла, время и объём соединений остаются наблюдаемыми. TLS/REALITY не обещают невидимость VPN."))
        add(WatsonFinding("own_uid","Запросы самого VPN требуют отдельного аудита","Приложение исключено из собственного TUN для подключения к upstream. Это не доказательство, что все его дополнительные запросы идут через VPN."))
    }
}
