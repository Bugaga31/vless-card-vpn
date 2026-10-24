package com.vlesscardvpn.tor

import android.content.Context
import com.vlesscardvpn.core.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Opt-in, bounded source refresh. Existing Tor connections are never restarted by a catalog update. */
object TorBridgeUpdates {
    private val mutex = Mutex()
    private val guard = Any()
    private val generation = AtomicLong()
    val status = MutableStateFlow("Список ещё не обновлялся в этой сессии")
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("tor_bridge_updates", Context.MODE_PRIVATE)
    // No guard lock here: Store.wipe owns Store's lock, while refresh locks guard before Store.update.
    fun consentCleared() { generation.incrementAndGet(); status.value = "Автообновление выключено · согласие сброшено" }
    fun lastUpdated(ctx: Context, transport: String) = prefs(ctx).getLong("success:$transport", 0)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, value: Boolean) = synchronized(guard) {
        generation.incrementAndGet()
        prefs(ctx).edit().putBoolean("enabled", value).apply()
        status.value = if (value) "Автообновление включено · раз в 6 часов при работе Tor" else "Автообновление выключено · мосты сохранены"
    }

    suspend fun refresh(ctx: Context, manual: Boolean = false) = mutex.withLock {
        val app = ctx.applicationContext
        val settings = Store.state.value.settings
        val transport = settings.torBridge
        if (transport !in setOf("obfs4", "webtunnel")) return@withLock
        val p = prefs(app)
        val now = System.currentTimeMillis()
        if (!manual && !TorBridgeRefreshPolicy.due(enabled(app), transport, now,
                p.getLong("success:$transport", 0), p.getLong("attempt:$transport", 0))) return@withLock
        val ticket = generation.get()
        p.edit().putLong("attempt:$transport", now).apply()
        status.value = "Загружаю список $transport…"
        try {
            val catalog = TorBridgeCatalog.fetch()
            currentCoroutineContext().ensureActive()
            var applied = false
            synchronized(guard) {
                Store.update { state ->
                    val active = state.settings
                    val replacement = TorBridgeRefreshPolicy.replacement(catalog, transport, settings.torBridgeLines,
                        active.torBridge, active.torBridgeLines, manual || enabled(app), ticket, generation.get())
                    if (replacement == null) state else {
                        applied = true
                        state.copy(settings = active.copy(torBridgeLines = replacement))
                    }
                }
                if (applied) {
                    p.edit().putLong("success:$transport", System.currentTimeMillis()).apply()
                    status.value = "Сохранено ${catalog.forTransport(transport).size} мостов $transport · применятся при следующем подключении. Доступность ещё не проверена."
                } else if (ticket == generation.get()) {
                    status.value = "Нет подходящих мостов или настройки изменены. Прежний список сохранён."
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (ticket == generation.get()) status.value = "Источник недоступен или список некорректен. Прежние мосты сохранены."
        }
    }
}
