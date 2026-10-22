package com.vlesscardvpn.core

/** Separate cheap reachability and actual service budgets; no positive result is invented on timeout. */
object ServiceProbePolicy {
    const val SERVICE_MS = 8000L
    const val FINAL_MS = 10000L
    const val TELEGRAM_MS = 8000L
    const val DC_MS = 4000L
    const val FINALISTS = 2
    const val PARALLEL = 2
    const val FOREGROUND_MS = AutoForegroundAttempt.TARGET_MS
    fun reason(code: String): String = when (code) {
        "mtproto-timeout", "deadline" -> "не хватило времени на протокольный ответ"
        "cancelled" -> "проверка отменена"
        "skipped-after-mtproto-fail" -> "проверка видео остановлена после отказа Telegram"
        "mtproto-invalid" -> "ответ MTProto не прошёл проверку"
        "mtproto-connect" -> "не удалось установить MTProto-соединение"
        "player-login-required" -> "контрольный плеер требует вход или проверку от ботов"
        "player-unplayable" -> "контрольное видео недоступно"
        "player-no-direct-media" -> "плеер не предоставил проверяемый адрес видео"
        "player-unavailable", "player-transport" -> "не удалось получить контрольный плеер"
        "early-eof" -> "медиапередача оборвалась раньше лимита"
        "media-transport" -> "ошибка передачи видеоданных"
        "worker-busy" -> "очередь проверок занята"
        else -> if (code.startsWith("media-http-") || code.startsWith("player-http-"))
            "контрольный ресурс вернул HTTP " + code.substringAfterLast('-').toIntOrNull().let { it ?: 0 }
        else "подтверждения пока нет"
    }
    fun describe(probe: Probe): String {
        probe.web?.let { web ->
            fun label(value: Boolean?) = when(value) { true -> "доступен"; false -> "сайт отказал в доступе"; null -> "не удалось проверить" }
            return "Telegram Web: ${label(web.telegram)}. YouTube сайт: ${label(web.youtube)}. Это не проверка сообщений и воспроизведения видео."
        }
        fun label(value: Boolean?, reasonCode: String) = when (value) { true -> "проверен"; false -> "не прошёл: ${reason(reasonCode)}"; null -> "не подтверждён: не удалось проверить — ${reason(reasonCode)}" }
        return "Telegram MTProto: ${label(probe.tgOk,probe.tgReason)}. YouTube-медиа: ${label(probe.ytOk,probe.ytReason)}."
    }
    /** Parallel media transfers are not comparable throughput samples. */
    fun isolatedSpeed(probe: Probe, parallel: Int): Int = when {
        parallel != 1 -> 0
        probe.httpsChecked -> probe.kbps.coerceAtLeast(0)
        probe.ytOk == true && probe.ytKbps > 0 -> probe.ytKbps
        else -> probe.kbps.coerceAtLeast(0)
    }
}

class ServiceProofException(val proof: Probe) : IllegalStateException("HTTPS-ресурсы: ${proof.httpsHosts}/2. " + ServiceProbePolicy.describe(proof))

/** Per-search diagnostics only, not proof of the current tunnel and never persisted or uploaded. */
class AutoSearchReport {
    data class Coverage(val catalog: Int=0,val eligible: Int=0,val tcp: Int=0,val reachable: Int=0,val https: Int=0,val data: Int=0) {
        fun text()="Каталог $catalog · допущено в Auto $eligible · TCP/адреса $tcp · доступны $reachable · HTTPS $https · передача данных $data"
    }
    companion object {
        val coverage=kotlinx.coroutines.flow.MutableStateFlow(Coverage())
        private val sequence=java.util.concurrent.atomic.AtomicLong()
    }
    private val token=sequence.incrementAndGet()
    private var all=0;private var eligible=0;private var lastPublish=0L
    private val tcpIds=HashSet<String>();private val reachableIds=HashSet<String>();private val httpsIds=HashSet<String>();private val dataIds=HashSet<String>()
    init { if(sequence.get()==token) coverage.value=Coverage() }
    @Synchronized fun catalog(total: Int, eligible: Int) { all=maxOf(all,total);this.eligible=maxOf(this.eligible,eligible);flush() }
    @Synchronized fun tcpStarted(id: String) { tcpIds+=id;publish() }
    @Synchronized fun tcpResult(id: String, ms: Int) { if(ms>0)reachableIds+=id;publish() }
    @Synchronized fun httpsResult(id: String, p: Probe) { httpsIds+=id;if(p.httpsHosts>=2)dataIds+=id;publish() }
    private fun publish() { if(System.nanoTime()-lastPublish>=150000000L) flush() }
    @Synchronized fun snapshot()=Coverage(all,eligible,tcpIds.size,reachableIds.size,httpsIds.size,dataIds.size)
    @Synchronized fun flush() { lastPublish=System.nanoTime();if(sequence.get()==token)coverage.value=snapshot() }
    private val results = linkedMapOf<String, Probe>()
    @Synchronized fun record(route: String, probe: Probe) {
        results[route] = probe
        while (results.size > 64) results.remove(results.keys.first())
    }
    @Synchronized fun resourceUnavailable(): Boolean = results.values.count {
        it.ytOk == null && it.ytReason in setOf("player-login-required", "player-unplayable", "player-no-direct-media")
    } >= 2
    @Synchronized fun summary(): String {
        val values = results.values.toList()
        val https = values.count { it.works }; val tg = values.count { it.tgOk == true }; val yt = values.count { it.ytOk == true }
        val note = values.lastOrNull { it.ytReason.startsWith("player-") && it.ytOk == null }?.let {
            " " + ServiceProbePolicy.reason(it.ytReason) + ". Это не доказательство отказа всех серверов."
        }.orEmpty()
        flush()
        return snapshot().text()+". Сервисные проверки: " + "Проверено маршрутов: ${values.size}; HTTPS: $https; Telegram MTProto: $tg; YouTube-медиа: $yt. " +
            "Это отдельные результаты, не подтверждение общей цепочки." + note
    }
}

/** A queued Main-thread callback cannot resurrect a timed-out or cancelled foreground search. */
internal class AutoSearchHandoff {
    private val state = java.util.concurrent.atomic.AtomicInteger(0)
    fun claim(): Boolean = state.compareAndSet(0, 1)
    /** True if the search closed before handing a candidate to TunnelService. */
    fun expire(): Boolean = state.compareAndSet(0, 2) || state.get() == 2
}

/** Only handed directly from final-chain verification to its immediate health check; RAM, 30s maximum. */
internal data class RecentServiceProof(val probe: Probe, val network: String, val handle: String?, val at: Long) {
    fun matches(network: String, handle: String?, now: Long): Boolean =
        (AutoEligibility.servicesWork(probe) || AutoEligibility.connectionWorks(probe)) && this.network == network && this.handle == handle && now - at in 0..30_000
}
