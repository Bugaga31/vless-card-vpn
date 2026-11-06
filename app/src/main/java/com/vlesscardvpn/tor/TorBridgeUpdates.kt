package com.vlesscardvpn.tor

import android.content.Context
import com.vlesscardvpn.core.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * User-consented bounded refresh of mandatory-bridge Tor from two sources: Tor Project's own distribution ([TorMoat],
 * through the VPN only) and the igareck list. A catalog update alone never restarts a
 * working Tor; only a start or a route that stopped working takes other bridges (see [replacementFor]).
 */
object TorBridgeUpdates {
    private val mutex = Mutex()
    private val guard = Any()
    private val generation = AtomicLong()
    /** Bridges this process already swapped away from, per transport: a rotation walks the whole saved catalog. */
    private val tried = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    val status = MutableStateFlow("Список ещё не обновлялся в этой сессии")
    /** What Tor last said about the running bridges; null until it answered for the current daemon. */
    val health = MutableStateFlow<TorBridgeMemory.Health?>(null)
    fun resetHealth() { health.value = null }
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("tor_bridge_updates", Context.MODE_PRIVATE)
    // No guard lock here: Store.wipe owns Store's lock, while refresh locks guard before Store.update.
    fun consentCleared() { generation.incrementAndGet(); tried.clear(); status.value = "Автообновление выключено · согласие сброшено" }

    /**
     * Valid lines of the last fetched catalog for [transport] (re-validated: prefs are only a cache), then Tor
     * Project's built-in bridges as the last resort of a rotation.
     */
    fun pool(ctx: Context, transport: String): List<String> =
        (prefs(ctx).getString("pool:$transport", null).orEmpty().lineSequence()
            .mapNotNull { TorBridgeCatalog.normalize(it) }.filter { it.startsWith("Bridge $transport ") }
            .distinct().take(TorBridgeRefreshPolicy.POOL_MAX).toList() + TorBuiltinBridges.lines(transport)).distinct()

    /** Tor Project bridges kept from earlier answers ([TorMoat.merge]); re-validated like the pool. */
    private fun officialKept(p: android.content.SharedPreferences, transport: String): List<String> =
        p.getString("official:$transport", null).orEmpty().lineSequence().mapNotNull { TorBridgeCatalog.normalize(it) }
            .filter { it.startsWith("Bridge $transport ") }.take(TorMoat.KEEP_MAX).toList()

    /** Tor's remembered verdicts for [transport] ([TorBridgeMemory]); cleared with the app data. */
    fun memory(ctx: Context, transport: String): TorBridgeMemory.State {
        val p = prefs(ctx)
        return TorBridgeMemory.decode(p.getString("good:$transport", null), p.getString("bad:$transport", null), transport)
    }

    /** Records what Tor said about the running [lines]; written only when something changed. */
    fun noteHealth(ctx: Context, transport: String, lines: List<String>, guards: Map<String, String>?, now: Long = System.currentTimeMillis()) {
        if (guards.isNullOrEmpty() || transport !in setOf("obfs4", "webtunnel")) return
        health.value = TorBridgeMemory.health(transport, lines, guards)
        val app = ctx.applicationContext
        synchronized(guard) {
            val ticket = generation.get()
            val before = memory(app, transport)
            val after = TorBridgeMemory.note(before, lines, guards, now)
            if (after != before && ticket == generation.get()) prefs(app).edit()
                .putString("good:$transport", TorBridgeMemory.encodeGood(after))
                .putString("bad:$transport", TorBridgeMemory.encodeBad(after)).apply()
        }
    }

    /**
     * A start with no usable bridge of [transport] (nothing pasted yet, the source unreachable): Tor Project's
     * built-in bridges go in front of the user's text instead of no Tor at all. Same guard as a rotation; returns
     * the lines to start with, or null (no built-ins for the transport, updates off, or the list changed meanwhile).
     */
    fun adoptBuiltins(ctx: Context, transport: String): List<String>? {
        val app = ctx.applicationContext
        val builtins = TorBuiltinBridges.lines(transport)
        if (builtins.isEmpty() || !enabled(app)) return null
        return synchronized(guard) {
            val ticket = generation.get()
            val before = Store.state.value.settings
            if (before.torBridge != transport || runCatching { TorBridgeCatalog.selected(before.torBridgeLines, transport) }.isSuccess) return@synchronized null
            val text = (builtins + listOfNotNull(before.torBridgeLines.takeIf { it.isNotBlank() })).joinToString("\n")
            if (runCatching { TorBridgeCatalog.selected(text, transport) }.getOrNull() != builtins) return@synchronized null
            var applied = false
            Store.update { state ->
                val active = state.settings
                if (ticket != generation.get() || !enabled(app) || active.torBridge != transport || active.torBridgeLines != before.torBridgeLines) state
                else { applied = true; state.copy(settings = active.copy(torBridgeLines = text)) }
            }
            if (applied) status.value = "Своих мостов $transport нет · используются встроенные мосты Tor Project; список обновится в фоне"
            builtins.takeIf { applied }
        }
    }

    /**
     * Swaps [failing] for untried bridges of the saved catalog, under the same guard as a refresh: consent on, the
     * transport and the user's list unchanged meanwhile. Returns the applied lines, or null when nothing new exists.
     */
    fun rotate(ctx: Context, transport: String, failing: List<String>, guards: Map<String, String>? = null): List<String>? {
        val app = ctx.applicationContext
        if (!enabled(app)) return null
        val pool = pool(app, transport)
        val now = System.currentTimeMillis()
        val memory = memory(app, transport)
        val keep = guards?.let { TorBridgeMemory.working(failing, it) }.orEmpty()
        val bad = TorBridgeMemory.bad(memory, now)
        return synchronized(guard) {
            val ticket = generation.get()
            val before = Store.state.value.settings
            if (before.torBridge != transport) return@synchronized null
            val seen = tried.getOrPut(transport) { java.util.Collections.synchronizedSet(linkedSetOf()) }
            seen += failing
            fun attempt(skip: Set<String>) = TorBridgeRefreshPolicy.rotation(pool, transport, failing, seen, keep = keep, good = memory.good, bad = skip)
            // Untried first; then the whole saved catalog again; recently failed bridges only when nothing else is left.
            val next = attempt(bad) ?: run { seen.clear(); seen += failing; attempt(bad) } ?: attempt(emptySet())
                ?: return@synchronized null
            var applied = false
            Store.update { state ->
                val active = state.settings
                if (ticket != generation.get() || !enabled(app) || active.torBridge != transport || active.torBridgeLines != before.torBridgeLines) state
                else { applied = true; state.copy(settings = active.copy(torBridgeLines = next.joinToString("\n"))) }
            }
            if (applied) {
                val builtin = TorBuiltinBridges.lines(transport).toSet().let { b -> next.count { it in b } }
                status.value = (if (keep.isEmpty()) "Мосты $transport не отвечали · Tor переподключён с другими ${next.size} из сохранённого списка"
                    else "Часть мостов $transport не отвечала · рабочие (${keep.size}) оставлены, остальные заменены из сохранённого списка") +
                    (if (builtin > 0) " · встроенных мостов Tor Project: $builtin" else "")
            }
            next.takeIf { applied }
        }
    }

    /**
     * Bridges for restarting a stalled or lost Tor: a fresh source list when one is due (consent and the stalled
     * cadence still apply), otherwise untried bridges from the saved catalog. null = nothing different to try.
     */
    suspend fun replacementFor(ctx: Context, transport: String, failing: List<String>, guards: Map<String, String>? = null): List<String>? {
        noteHealth(ctx, transport, failing, guards)
        if (refresh(ctx, stalled = true, keep = guards?.let { TorBridgeMemory.working(failing, it) }.orEmpty())) {
            val current = Store.state.value.settings
            val fresh = runCatching { TorBridgeCatalog.selected(current.torBridgeLines, transport) }.getOrNull()
            if (current.torBridge == transport && fresh != null && fresh.toSet() != failing.toSet()) return fresh
        }
        currentCoroutineContext().ensureActive()
        return rotate(ctx, transport, failing, guards)
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
    suspend fun refresh(ctx: Context, manual: Boolean = false, stalled: Boolean = false, keep: List<String> = emptyList()): Boolean = mutex.withLock {
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
        status.value = "Загружаю мосты $transport: Tor Project и igareck…"
        try {
            // Through the running tunnel first: the source is often unreachable on the bare network. Tor's own uplink
            // goes straight to VLESS; the main SOCKS may send all TCP into Tor, which cannot answer before bridges work.
            val via = com.vlesscardvpn.core.Tunnel.torUplink ?: com.vlesscardvpn.core.Tunnel.socks?.port
            val (moat, igareck) = coroutineScope {
                // Tor Project's own bridges only through the VPN: the request must not be seen on the bare network.
                val official = via?.let { port -> async { try { TorMoat.fetch(port) } catch (e: Exception) { if (e is CancellationException) throw e; null } } }
                val list = try {
                    via?.let { port ->
                        try { TorBridgeCatalog.fetch(port, timeoutSec = 6) } catch (e: Exception) { if (e is CancellationException) throw e; null }
                    } ?: TorBridgeCatalog.fetch()
                } catch (e: Exception) { if (e is CancellationException) throw e; null }
                official?.await() to list
            }
            if (moat?.lines.isNullOrEmpty() && igareck == null) error("No bridge source answered")
            // Tor Project's bridges first (tested by the source, handed out a couple at a time), then the igareck list.
            val official = TorMoat.merge(moat?.lines.orEmpty(), listOf("obfs4", "webtunnel").flatMap { officialKept(p, it) })
            // One line per bridge: the same fingerprint in both lists stays in its Tor Project form.
            val catalog = TorBridgeCatalog.Parsed((official + igareck?.lines.orEmpty()).distinctBy { TorBridgeMemory.fingerprint(it) ?: it },
                (moat?.rejected ?: 0) + (igareck?.rejected ?: 0))
            currentCoroutineContext().ensureActive()
            val remembered = memory(app, transport)
            val kept = if (stalled && !manual) keep else remembered.good.take(TorBridgeRefreshPolicy.KEEP_GOOD)
            var applied = false
            var saved = emptyList<String>()
            synchronized(guard) {
                if (ticket == generation.get() && (manual || enabled(app))) {
                    val edit = p.edit()
                    for (t in listOf("obfs4", "webtunnel")) {
                        val lines = catalog.lines.filter { it.startsWith("Bridge $t ") }.take(TorBridgeRefreshPolicy.POOL_MAX)
                        if (lines.isNotEmpty()) edit.putString("pool:$t", lines.joinToString("\n"))
                        edit.putString("official:$t", official.filter { it.startsWith("Bridge $t ") }.joinToString("\n"))
                    }
                    edit.apply()
                }
                Store.update { state ->
                    val active = state.settings
                    // A stalled start asks for bridges other than the ones that just failed, when the source has more.
                    val avoid = if (stalled && !manual) runCatching { TorBridgeCatalog.selected(settings.torBridgeLines, transport).toSet() }
                        .getOrDefault(emptySet()) else emptySet()
                    // A stalled start keeps what Tor reports working now; a regular refresh keeps bridges that worked before.
                    val replacement = TorBridgeRefreshPolicy.replacement(catalog, transport, settings.torBridgeLines,
                        active.torBridge, active.torBridgeLines, manual || enabled(app), ticket, generation.get(), avoid,
                        keep = kept, bad = TorBridgeMemory.bad(remembered, now))
                    if (replacement == null) state else {
                        applied = true
                        saved = replacement.lines()
                        state.copy(settings = active.copy(torBridgeLines = replacement))
                    }
                }
                if (applied) {
                    p.edit().putLong("success:$transport", System.currentTimeMillis()).apply()
                    status.value = "Сохранено ${saved.size} мостов $transport" + TorBridgeRefreshPolicy.sources(saved, kept, official) +
                        " · Tor возьмёт их при следующем запуске или если текущие перестанут отвечать. Доступность новых ещё не проверена."
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
