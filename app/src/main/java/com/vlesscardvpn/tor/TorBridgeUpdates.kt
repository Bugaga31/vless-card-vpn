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

/**
 * User-consented bounded igareck source refresh for mandatory-bridge Tor. A catalog update alone never restarts a
 * working Tor; only a start or a route that stopped working takes other bridges (see [replacementFor]).
 */
object TorBridgeUpdates {
    private val mutex = Mutex()
    private val guard = Any()
    private val generation = AtomicLong()
    /** Bridges this process already swapped away from, per transport: a rotation walks the whole saved catalog. */
    private val tried = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    val status = MutableStateFlow("Список ещё не обновлялся в этой сессии")
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("tor_bridge_updates", Context.MODE_PRIVATE)
    // No guard lock here: Store.wipe owns Store's lock, while refresh locks guard before Store.update.
    fun consentCleared() { generation.incrementAndGet(); tried.clear(); status.value = "Автообновление выключено · согласие сброшено" }

    /** Valid lines of the last fetched catalog for [transport] (re-validated: prefs are only a cache). */
    fun pool(ctx: Context, transport: String): List<String> =
        prefs(ctx).getString("pool:$transport", null).orEmpty().lineSequence()
            .mapNotNull { TorBridgeCatalog.normalize(it) }.filter { it.startsWith("Bridge $transport ") }
            .distinct().take(TorBridgeRefreshPolicy.POOL_MAX).toList()

    /**
     * Swaps [failing] for untried bridges of the saved catalog, under the same guard as a refresh: consent on, the
     * transport and the user's list unchanged meanwhile. Returns the applied lines, or null when nothing new exists.
     */
    fun rotate(ctx: Context, transport: String, failing: List<String>): List<String>? {
        val app = ctx.applicationContext
        if (!enabled(app)) return null
        val pool = pool(app, transport)
        return synchronized(guard) {
            val ticket = generation.get()
            val before = Store.state.value.settings
            if (before.torBridge != transport) return@synchronized null
            val seen = tried.getOrPut(transport) { java.util.Collections.synchronizedSet(linkedSetOf()) }
            seen += failing
            val next = TorBridgeRefreshPolicy.rotation(pool, transport, failing, seen)
                ?: run { seen.clear(); seen += failing; TorBridgeRefreshPolicy.rotation(pool, transport, failing, seen) }
                ?: return@synchronized null
            var applied = false
            Store.update { state ->
                val active = state.settings
                if (ticket != generation.get() || !enabled(app) || active.torBridge != transport || active.torBridgeLines != before.torBridgeLines) state
                else { applied = true; state.copy(settings = active.copy(torBridgeLines = next.joinToString("\n"))) }
            }
            if (applied) status.value = "Мосты $transport не отвечали · Tor переподключён с другими ${next.size} из сохранённого списка"
            next.takeIf { applied }
        }
    }

    /**
     * Bridges for restarting a stalled or lost Tor: a fresh source list when one is due (consent and the stalled
     * cadence still apply), otherwise untried bridges from the saved catalog. null = nothing different to try.
     */
    suspend fun replacementFor(ctx: Context, transport: String, failing: List<String>): List<String>? {
        if (refresh(ctx, stalled = true)) {
            val current = Store.state.value.settings
            val fresh = runCatching { TorBridgeCatalog.selected(current.torBridgeLines, transport) }.getOrNull()
            if (current.torBridge == transport && fresh != null && fresh.toSet() != failing.toSet()) return fresh
        }
        currentCoroutineContext().ensureActive()
        return rotate(ctx, transport, failing)
    }
    fun lastUpdated(ctx: Context, transport: String) = prefs(ctx).getLong("success:$transport", 0)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, value: Boolean) = synchronized(guard) {
        generation.incrementAndGet()
        prefs(ctx).edit().putBoolean("enabled", value).apply()
        status.value = if (value) "Автообновление включено · раз в 6 часов при работе Tor" else "Автообновление выключено · мосты сохранены"
    }

    /**
     * [stalled]: the running start made no progress on the saved bridges; the source may be asked earlier than the
     * 6-hour cadence (see [TorBridgeRefreshPolicy.dueWhenStalled]). Returns true when a new list was saved.
     */
    suspend fun refresh(ctx: Context, manual: Boolean = false, stalled: Boolean = false): Boolean = mutex.withLock {
        val app = ctx.applicationContext
        val settings = Store.state.value.settings
        val transport = settings.torBridge
        if (transport !in setOf("obfs4", "webtunnel")) return@withLock false
        val p = prefs(app)
        val now = System.currentTimeMillis()
        val success = p.getLong("success:$transport", 0); val attempted = p.getLong("attempt:$transport", 0)
        if (!manual && !TorBridgeRefreshPolicy.due(enabled(app), transport, now, success, attempted) &&
            !(stalled && TorBridgeRefreshPolicy.dueWhenStalled(enabled(app), transport, now, success, attempted))) return@withLock false
        val ticket = generation.get()
        p.edit().putLong("attempt:$transport", now).apply()
        status.value = "Загружаю список $transport…"
        try {
            // Through the running tunnel first: the source is often unreachable on the bare network.
            val via = com.vlesscardvpn.core.Tunnel.socks?.port
            val catalog = via?.let { port ->
                try { TorBridgeCatalog.fetch(port, timeoutSec = 6) } catch (e: Exception) { if (e is CancellationException) throw e; null }
            } ?: TorBridgeCatalog.fetch()
            currentCoroutineContext().ensureActive()
            var applied = false
            synchronized(guard) {
                if (ticket == generation.get() && (manual || enabled(app))) {
                    val edit = p.edit()
                    for (t in listOf("obfs4", "webtunnel")) {
                        val lines = catalog.lines.filter { it.startsWith("Bridge $t ") }.take(TorBridgeRefreshPolicy.POOL_MAX)
                        if (lines.isNotEmpty()) edit.putString("pool:$t", lines.joinToString("\n"))
                    }
                    edit.apply()
                }
                Store.update { state ->
                    val active = state.settings
                    // A stalled start asks for bridges other than the ones that just failed, when the source has more.
                    val avoid = if (stalled && !manual) runCatching { TorBridgeCatalog.selected(settings.torBridgeLines, transport).toSet() }
                        .getOrDefault(emptySet()) else emptySet()
                    val replacement = TorBridgeRefreshPolicy.replacement(catalog, transport, settings.torBridgeLines,
                        active.torBridge, active.torBridgeLines, manual || enabled(app), ticket, generation.get(), avoid)
                    if (replacement == null) state else {
                        applied = true
                        state.copy(settings = active.copy(torBridgeLines = replacement))
                    }
                }
                if (applied) {
                    p.edit().putLong("success:$transport", System.currentTimeMillis()).apply()
                    status.value = "Сохранено ${catalog.forTransport(transport).size} мостов $transport · Tor возьмёт их при следующем запуске или если текущие перестанут отвечать. Доступность ещё не проверена."
                } else if (ticket == generation.get()) {
                    status.value = "Нет подходящих мостов или настройки изменены. Прежний список сохранён."
                }
            }
            applied
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (ticket == generation.get()) status.value = "Источник недоступен или список некорректен. Прежние мосты сохранены."
            false
        }
    }
}
