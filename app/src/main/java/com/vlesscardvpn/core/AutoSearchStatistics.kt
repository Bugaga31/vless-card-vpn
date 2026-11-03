package com.vlesscardvpn.core

/** Aggregate ordinary foreground probe outcomes, never endpoint names or a claim of a DPI block. */
class AutoSearchStatistics(private val maxMs:Int = AutoLatencyPolicy.MAX_MS) {
    private var started=0;private var completed=0
    private var ready=0;private var https=0;private var telegram=0;private var unknown=0;private var slow=0
    @Synchronized fun start() {started++}
    @Synchronized fun observe(works:Boolean,realMs:Int,tgOk:Boolean?,tgMs:Int) {
        completed++
        when {
            !works || realMs<=0 -> https++
            tgOk==false -> telegram++
            tgOk!=true || tgMs<=0 -> unknown++
            realMs>maxMs || tgMs>maxMs -> slow++
            else -> ready++
        }
    }
    @Synchronized fun snapshot():Map<String,Int> = linkedMapOf(
        "started" to started,"completed" to completed,"ready" to ready,
        "https_unconfirmed" to https,"telegram_unconfirmed" to telegram,
        "telegram_unknown" to unknown,"over_${maxMs}ms" to slow,
        "unfinished" to (started-completed).coerceAtLeast(0))
    @Synchronized fun explanation():String = "Обычные пробы: запущено $started, завершено $completed. " +
        "HTTPS не подтверждён: $https; Telegram не подтверждён: $telegram; " +
        "Telegram без замера: $unknown; выше ${maxMs} мс: $slow. " +
        "Незавершённые пробы: ${(started-completed).coerceAtLeast(0)} — это не отказ сервера."
}
