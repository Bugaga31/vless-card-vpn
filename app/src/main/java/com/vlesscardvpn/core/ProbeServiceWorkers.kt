package com.vlesscardvpn.core

import java.util.concurrent.*
/** Up to five optional tasks per Auto candidate. Other candidates' downloads cannot
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
        "IG_LOGIN", "IG_REDIRECT", "IG_HTTP", "IG_FORMAT", "YT_RANGE_UNSUPPORTED", "YT_MEDIA_TRUNCATED",
        "VOICE_INCOMPLETE", "VOICE_NO_RESPONSE", "VOICE_LATENCY", "VOICE_AVAILABLE_LOSS", "YT_NO_DIRECT_MEDIA", "YT_HTTP", "YT_FORMAT", "YT_LOGIN_REQUIRED", "YT_UNPLAYABLE", "YT_SIGNATURE_UNSUPPORTED", "DISCORD_HTTP", "DISCORD_FORMAT", "DISCORD_GATEWAY",
        "VOICE_UNKNOWN", "VOICE_NO_UDP", "VOICE_LOSS", "VOICE_SLOW", "VOICE_UNSTABLE", "VOICE_CAUTION",
        "STABILITY_RESPONSE_FAILED", "STABILITY_INCOMPLETE", "STABILITY_SPREAD", "STABILITY_LATENCY" -> ServiceProbeStatus.label(true,null,code)
        "TIMEOUT"->"время проверки истекло"
        "CANCELLED"->"проверка отменена"
        "ERROR"->"внутренняя ошибка проверки"
        else->"определённый результат не получен"
    }
}
