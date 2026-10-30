package com.vlesscardvpn.core

import java.util.concurrent.*
/** Up to four optional tasks per Auto candidate. Other candidates' downloads cannot
 * consume its Discord slot. Callers already bound candidates with ProbeConcurrencyPolicy.
 * Lazy threads and a four-entry queue; closed after each probe, never per catalogue entry. */
class ProbeServiceWorkers:AutoCloseable {
    val executor=ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(4),
        ThreadFactory {r->Thread(r,"auto-service").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    val telegram=ThreadPoolExecutor(2,2,0L,TimeUnit.MILLISECONDS,ArrayBlockingQueue<Runnable>(2),
        ThreadFactory {r->Thread(r,"auto-telegram").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
    override fun close() {executor.shutdownNow();telegram.shutdownNow()}
}
object ProbeTaskNotes {
    fun label(code:String?)=when(code) {
        "YT_NO_DIRECT_MEDIA", "YT_HTTP", "YT_FORMAT", "DISCORD_HTTP", "DISCORD_FORMAT" -> ServiceProbeStatus.label(true,null,code)
        "TIMEOUT"->"время проверки истекло"
        "CANCELLED"->"проверка отменена"
        "ERROR"->"внутренняя ошибка проверки"
        else->"определённый результат не получен"
    }
}
