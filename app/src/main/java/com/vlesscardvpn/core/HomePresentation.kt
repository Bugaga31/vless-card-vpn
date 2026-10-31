package com.vlesscardvpn.core

/** Presentation only. This policy never admits a route, starts a probe or changes VPN settings. */
object HomePresentation {
    enum class Action { CONNECT, CANCEL, DISCONNECT }
    enum class Verdict(val symbol:String,val label:String) {
        PASS("✓","Ответ получен"), FAIL("✕","Проверка не прошла"), UNKNOWN("?","Неизвестно"),
        NOT_REQUIRED("—","Не выбрано"), NO_DATA("—","Нет данных"), STALE("!","Замер устарел")
    }
    data class Service(val id:String,val title:String,val verdict:Verdict,val detail:String="")
    fun action(connected:Boolean,starting:Boolean)=when {connected->Action.DISCONNECT;starting->Action.CANCEL;else->Action.CONNECT}
    fun verdict(required:Boolean,value:Boolean?,hasSample:Boolean,current:Boolean):Verdict=when {
        !required->Verdict.NOT_REQUIRED
        !hasSample->Verdict.NO_DATA
        !current->Verdict.STALE
        value==true->Verdict.PASS
        value==false->Verdict.FAIL
        else->Verdict.UNKNOWN
    }
    fun fresh(at:Long,now:Long,maxAge:Long=90_000)=at>0 && now>=at && now-at<=maxAge
    /** No arbitrary exception, endpoint, URL or user label is rendered as a diagnostic. */
    fun note(code:String?):String = when(code) {
        "TIMEOUT"->"Истёк срок проверки"; "CANCELLED"->"Проверка отменена"; "ERROR"->"Ошибка проверки"
        "YT_ACCESS_OK"->"YouTube отвечает; видео не проверялось"
        "YT_ACCESS_HTTP"->"YouTube не подтвердил HTTP-доступ"
        "YT_ACCESS_REDIRECT"->"Запрос YouTube перенаправлен"
        "YT_RATE_LIMITED"->"YouTube ограничил частоту запросов"
        "YT_MEDIA_RESTRICTED"->"Видеоссылка ограничена или истекла"
        "YT_REDIRECT"->"Перенаправление видео не подтверждено"
        "YT_RANGE_INVALID"->"Неверный диапазон видеоданных"
        "YT_CONTAINER_INVALID"->"Контейнер видео не подтверждён"
        "YT_LOGIN_REQUIRED"->"YouTube требует вход или проверку пользователя"
        "YT_SIGNATURE_UNSUPPORTED"->"Подпись видеоссылки пока не поддерживается"
        "YT_UNPLAYABLE"->"Сервис не разрешил тестовое видео"
        "YT_NO_DIRECT_MEDIA"->"Прямая видеоссылка не получена"
        "YT_RANGE_UNSUPPORTED"->"Следующие участки видео не подтверждены"
        "YT_MEDIA_TRUNCATED"->"Загрузка участка оборвалась"
        "YT_HTTP","YT_FORMAT"->"Ограничение ответа медиапроверки"
        "IG_LOGIN","IG_REDIRECT","IG_HTTP","IG_FORMAT"->"Публичная страница Instagram не подтверждена"
        "DISCORD_GATEWAY"->"WebSocket HELLO не подтверждён"
        "DISCORD_HTTP","DISCORD_FORMAT"->"Discord не выдал ожидаемый ответ"
        "STABILITY_INCOMPLETE"->"Серия Telegram не завершена"
        "STABILITY_SPREAD"->"Большой разброс задержки Telegram"
        "STABILITY_LATENCY"->"Задержка серии выше предела"
        "STABILITY_RESPONSE_FAILED"->"Неуспешный ответ в серии"
        "VOICE_AVAILABLE_LOSS"->"UDP доступен с потерей части ответов"
        "VOICE_LATENCY"->"RTT UDP выше предела"
        "VOICE_INCOMPLETE","VOICE_UNKNOWN"->"UDP-серия не завершена"
        "VOICE_NO_RESPONSE","VOICE_NO_UDP"->"STUN не подтвердил UDP"
        null,""->""
        else->"Дополнительное подтверждение отсутствует"
    }
}
