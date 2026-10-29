package com.vlesscardvpn.core

/** Local measured counts and the next scheduled pause, never an ETA for a working VPN. */
object SearchFeedback {
    fun title(wait:AutoSearchCampaign.WaitState):String = when(wait.reason) {
        AutoSearchCampaign.WaitReason.OFFLINE -> "Авто: ожидаем сеть"
        AutoSearchCampaign.WaitReason.CATALOG_RETRY -> "Авто: ждём новые маршруты"
        AutoSearchCampaign.WaitReason.BACKOFF -> "Авто: пауза между проверками"
    }
    fun message(wait:AutoSearchCampaign.WaitState,counts:Map<String,Int>):String {
        val done=(counts["completed"] ?: 0).coerceAtLeast(0)
        val active=(counts["unfinished"] ?: 0).coerceAtLeast(0)
        val seconds=((wait.delayMs.coerceIn(0,60000)+999)/1000).coerceAtLeast(1)
        val state=when(wait.reason) {
            AutoSearchCampaign.WaitReason.OFFLINE -> "Нет сети. Ждём восстановления, серверы сейчас не проверяются."
            AutoSearchCampaign.WaitReason.CATALOG_RETRY -> "Маршруты текущего каталога уже были в очереди. Ждём новые серверы или повторную попытку; перепроверим очередь через $seconds с."
            AutoSearchCampaign.WaitReason.BACKOFF -> "Волна ${wait.round.toLong().coerceAtLeast(0)+1} завершена. Следующая волна через $seconds с."
        }
        return "$state Завершено обычных проб: $done; незавершённых: $active. Поиск можно отменить кнопкой выше."
    }
    fun counter(done:Int,total:Int,message:String):String? = when {
        total>0 -> ExperiencePolicy.counter(done,total)
        message.isBlank() -> "Подготавливаем проверку"
        else -> null
    }
    fun secondaryProgress(connecting:Boolean)=!connecting
}
