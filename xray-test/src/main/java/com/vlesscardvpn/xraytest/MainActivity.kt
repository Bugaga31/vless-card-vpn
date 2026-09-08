package com.vlesscardvpn.xraytest

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val probeConsentDefault = true
    private var entries by mutableStateOf<List<CardProfile>>(emptyList())
    private var working by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var permissionPending by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var work: Job? = null
    private var pending: Intent? = null
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val intent = pending; pending = null; permissionPending = false
        if (result.resultCode == RESULT_OK && intent != null) startVpn(intent)
        else { TestState.update(Session(message = "Разрешение VPN не выдано")); message = "Подключение отменено" }
    }
    private fun occupied() = working || permissionPending || TestState.session.value.let { it.active || it.busy }
    private fun startVpn(intent: Intent) {
        try {
            TestState.update(Session(busy = true, message = "Запуск VPN-сервиса…"))
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) { Reports.error(e); TestState.update(Session(message = "Android не разрешил запуск. Открой отчёт.")) }
    }
    private fun load() {
        if (working) return
        working = true
        work = lifecycleScope.launch {
            try { entries = LoungeStorage.load(applicationContext); loaded = true; message = "" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Reports.error(e); loaded = false; message = "Хранилище недоступно. Данные не удалены; попробуй открыть список ещё раз." }
            finally { working = false }
        }
    }
    private fun change(transform: (List<CardProfile>) -> List<CardProfile>) {
        if (occupied() || !loaded) return
        working = true
        work = lifecycleScope.launch {
            try { entries = LoungeStorage.change(applicationContext, transform); message = "Список сохранён" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Reports.error(e); message = "Не удалось сохранить список. Старые данные не удалены." }
            finally { working = false }
        }
    }
    private fun importProfiles(text: String) {
        if (occupied() || !loaded) return
        working = true
        work = lifecycleScope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) { PublicSources.parse(text, "Мой импорт") }
                val before = entries.size
                entries = LoungeStorage.change(applicationContext) { ProfileCatalog.merge(it, parsed.profiles) }
                message = "Добавлено ${entries.size - before}. Пропущено неподдерживаемых: ${parsed.skipped}. Лимит ${ProfileCatalog.LIMIT}."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Reports.error(e); message = "Импорт не выполнен. Проверь формат; старый список сохранён." }
            finally { working = false }
        }
    }
    private fun fetch(consent: Boolean) {
        if (!consent || occupied() || !loaded) return
        working = true
        work = lifecycleScope.launch {
            try {
                val result = PublicSources.fetch { message = it }
                val before = entries.size
                entries = LoungeStorage.change(applicationContext) { ProfileCatalog.merge(it, result.profiles) }
                message = "Добавлено ${entries.size - before}; неподдерживаемых ${result.skipped}; недоступных источников ${result.failures}/${PublicSources.sources.size}. Узлы ещё не проверены. Лимит ${ProfileCatalog.LIMIT}."
            } catch (e: CancellationException) { message = "Загрузка отменена"; throw e }
            catch (e: Exception) { Reports.error(e); message = "Не удалось сохранить загрузку. Старый список сохранён." }
            finally { working = false }
        }
    }
    private fun connect(auto: Boolean, favoritesOnly: Boolean, selected: String?, consent: Boolean) {
        if (!consent || occupied() || !loaded) return
        val candidates = ProfileCatalog.candidates(entries, auto, favoritesOnly, selected)
        if (candidates.isEmpty()) {
            if (auto && !favoritesOnly) {
                working = true
                message = "Авто-режим: автоматическая загрузка и подбор рабочих серверов…"
                lifecycleScope.launch {
                    try {
                        val result = PublicSources.fetch { message = it }
                        entries = LoungeStorage.change(applicationContext) { ProfileCatalog.merge(it, result.profiles) }
                        val freshCandidates = ProfileCatalog.candidates(entries, auto = true, favoritesOnly = false, selectedKey = null)
                        if (freshCandidates.isNotEmpty()) {
                            TestState.profiles.value = freshCandidates.map { it.node() }
                            RunningLabels.names = freshCandidates.map { it.name }
                            val intent = Intent(this@MainActivity, XrayVpnService::class.java).setAction("START").putExtra("auto", true).putExtra("consent", consent).putExtra("selected", 0)
                            val request = VpnService.prepare(this@MainActivity)
                            if (request == null) startVpn(intent)
                            else { pending = intent; permissionPending = true; permission.launch(request) }
                        } else {
                            message = "Не удалось загрузить серверы. Проверьте подключение к сети."
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        Reports.error(e)
                        message = "Ошибка автоматической загрузки серверов."
                    } finally {
                        working = false
                    }
                }
                return
            } else {
                message = "Нет подходящих серверов: выбери узел или включи авто-режим."
                return
            }
        }
        TestState.profiles.value = candidates.map { it.node() }
        RunningLabels.names = candidates.map { it.name }
        val intent = Intent(this, XrayVpnService::class.java).setAction("START").putExtra("auto", auto).putExtra("consent", consent).putExtra("selected", 0)
        try {
            val request = VpnService.prepare(this)
            if (request == null) startVpn(intent)
            else { pending = intent; permissionPending = true; permission.launch(request) }
        } catch (e: Exception) { Reports.error(e); permissionPending = false; pending = null; message = "Не удалось запросить разрешение VPN. Открой отчёт." }
    }
    private fun turbo(consent: Boolean) {
        if (!consent || occupied() || !loaded) return
        working = true
        work = lifecycleScope.launch {
            try {
                if (entries.isEmpty()) {
                    message = "Турбо: загружаю серверы из источников…"
                    val result = PublicSources.fetch { message = it }
                    entries = LoungeStorage.change(applicationContext) { ProfileCatalog.merge(it, result.profiles) }
                }
                if (entries.isEmpty()) { message = "Турбо: серверы не найдены. Добавь свои ссылки."; return@launch }
                message = "Турбо: измеряю задержку ${entries.size} серверов…"
                val nodes = entries.map { it.node() }
                val probes = TurboEngine.rank(nodes)
                val best = probes.filter { it.latencyMs >= 0 }.minByOrNull { it.latencyMs }
                if (best == null) { message = "Турбо: ни один сервер не отвечает. Проверь интернет."; return@launch }
                val ordered = TurboEngine.order(nodes.size, probes, TurboEngine.Strategy.FASTEST).map { entries[it] }
                TestState.profiles.value = ordered.map { it.node() }
                RunningLabels.names = ordered.map { it.name }
                message = "Турбо: лучший — ${entries[best.index].name} (${best.latencyMs} мс). Подключаю…"
                val intent = Intent(this@MainActivity, XrayVpnService::class.java).setAction("START").putExtra("auto", true).putExtra("consent", consent).putExtra("selected", 0)
                val request = VpnService.prepare(this@MainActivity)
                if (request == null) startVpn(intent)
                else { pending = intent; permissionPending = true; permission.launch(request) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Reports.error(e); message = "Турбо: ошибка запуска. Открой отчёт." }
            finally { working = false }
        }
    }
    private fun stop() {
        try { startService(Intent(this, XrayVpnService::class.java).setAction("STOP")) }
        catch (e: Exception) { Reports.error(e); message = "Не удалось отправить команду отключения." }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LoungeScreen(entries, working, loaded, permissionPending, message,
                onReload = { load() }, onImport = { importProfiles(it) }, onFetch = { fetch(it) }, onCancelFetch = { work?.cancel() },
                onFavorite = { key -> change { list -> list.map { if (it.key == key) it.copy(favorite = !it.favorite) else it } } },
                onDelete = { key -> change { list -> list.filterNot { it.key == key } } },
                onConnect = { auto, favorites, key, consent -> connect(auto, favorites, key, consent) }, onStop = { stop() },
                onTurbo = { turbo(probeConsentDefault) },
                report = { Reports.read(this) })
        }
        load()
    }
}
