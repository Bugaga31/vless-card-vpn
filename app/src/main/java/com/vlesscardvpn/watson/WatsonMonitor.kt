package com.vlesscardvpn.watson

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.*
import android.os.Build
import android.os.SystemClock
import com.vlesscardvpn.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** RAM-only launcher-visible permission inventory. No network, app control or VPN mutation. */
object WatsonMonitor {
    const val JOB_ID = 13402
    private val started = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private var completedGeneration = -1L
    private var lastAt = -1L
    private var lastAttemptAt = -1L
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _snapshot = MutableStateFlow<WatsonApps.Snapshot?>(null)
    val snapshot = _snapshot.asStateFlow()
    private val _status = MutableStateFlow("Автоаудит ожидает безопасного окна")
    val status = _status.asStateFlow()
    fun requestRefresh() { requests.trySend(Unit) }
    fun start(app: Application) {
        if (!started.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                generation.incrementAndGet(); requestRefresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") app.registerReceiver(receiver, filter)
        scope.launch {
            Store.settings.map { it.automaticSecurityAudit }.distinctUntilChanged().collectLatest { enabled ->
                val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (!enabled) {
                    scheduler.cancel(JOB_ID)
                    mutex.lock()
                    try { _snapshot.value = null; lastAt = -1; lastAttemptAt = -1; completedGeneration = -1
                        _status.value = "Автоаудит выключен" } finally { mutex.unlock() }
                } else {
                    val scheduled = runCatching {
                        val job = JobInfo.Builder(JOB_ID, ComponentName(app, DeviceCoachJob::class.java))
                            .setPeriodic(30 * 60_000L)
                        if (Build.VERSION.SDK_INT >= 26) job.setRequiresBatteryNotLow(true)
                        scheduler.schedule(job.build()) == JobScheduler.RESULT_SUCCESS
                    }.getOrDefault(false)
                    _status.value = if (scheduled) "Автоаудит включён · фон по расписанию Android"
                        else "Автоаудит включён · фоновое расписание недоступно"
                    requestRefresh()
                    for (request in requests) {
                        // Batch installer/package broadcast bursts without polling or changing VPN work.
                        delay(WatsonAuditPolicy.COALESCE_MS)
                        while (requests.tryReceive().isSuccess) { /* coalesce */ }
                        refresh(app)
                    }
                }
            }
        }
    }
    private fun allowed(ctx: Context) = WatsonAuditPolicy.allowed(Store.settings.value.automaticSecurityAudit,
        Actions.running.value, GameSession.active(), PhonePower.refresh(ctx).allowLight)
    suspend fun refresh(ctx: Context, force: Boolean = false) {
        if (!mutex.tryLock()) return
        try {
            if (!allowed(ctx)) {
                _status.value = if (!Store.settings.value.automaticSecurityAudit) "Автоаудит выключен"
                    else "Автоаудит отложен: поиск VPN или экономия ресурсов"
                return
            }
            val now = SystemClock.elapsedRealtime()
            val ticket = generation.get()
            if (!force && !WatsonAuditPolicy.due(now, lastAt, ticket, completedGeneration)) return
            if (!WatsonAuditPolicy.attemptDue(now, lastAttemptAt, force)) return
            lastAttemptAt = now
            _status.value = "Локальный аудит разрешений…"
            val result = withTimeout(WatsonAuditPolicy.SCAN_BUDGET_MS) {
                WatsonApps.scan(ctx) { allowed(ctx) }
            }
            currentCoroutineContext().ensureActive()
            if (!allowed(ctx)) throw CancellationException("Optional audit deferred")
            if (!WatsonAuditPolicy.publishable(ticket, generation.get())) {
                _status.value = "Приложения изменились во время аудита · снимок требует обновления"
                requestRefresh()
                return
            }
            _snapshot.value = result
            lastAt = SystemClock.elapsedRealtime(); completedGeneration = ticket
            _status.value = if (result.incomplete) "Аудит частичный · безопасность неизвестна"
                else "Разрешения обновлены · это не антивирусный вердикт"
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            _status.value = "Аудит отложен · предыдущий снимок не подтверждает текущие разрешения"
        } catch (_: Exception) {
            _status.value = "Аудит не завершён · безопасность неизвестна"
        } finally { mutex.unlock() }
    }
}
