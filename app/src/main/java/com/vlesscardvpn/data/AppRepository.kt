package com.vlesscardvpn.data

import android.content.Context
import android.content.SharedPreferences
import com.vlesscardvpn.data.db.AppDatabase
import com.vlesscardvpn.data.db.toDomain
import com.vlesscardvpn.data.db.toEntity
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.AutoConnectPolicy
import com.vlesscardvpn.domain.SubscriptionPolicy
import com.vlesscardvpn.util.UniversalConfigParser
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    private val prefs: SharedPreferences = context.getSharedPreferences("vless_vpn_prefs", Context.MODE_PRIVATE)
    private val rescuePrefs: SharedPreferences = context.getSharedPreferences("rescue_profiles_prefs", Context.MODE_PRIVATE)
    private val passportPrefs: SharedPreferences = context.getSharedPreferences("server_passport_prefs", Context.MODE_PRIVATE)
    private val _settingsFlow = MutableStateFlow(loadSettingsFromPrefs())
    val settingsFlow = _settingsFlow.asStateFlow()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settingsFlow.value = loadSettingsFromPrefs()
    }
    init { prefs.registerOnSharedPreferenceChangeListener(preferenceListener) }

    private val _storageIssue = MutableStateFlow<String?>(null)
    val storageIssueFlow = _storageIssue.asStateFlow()
    private val storageIssueText = "Ключ или защищённая запись недоступны. Данные не удалены. Не переустанавливайте приложение без резервной копии."
    val configsFlow: Flow<List<VlessConfig>> = db.vlessConfigDao().getAllFlow()
        .map { list -> list.map { it.toDomain() } }
        .catch { error ->
            if (error is CancellationException) throw error
            _storageIssue.value = storageIssueText
            emit(emptyList())
        }.flowOn(Dispatchers.IO)

    private suspend fun <T> guardedRead(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { _storageIssue.value = storageIssueText; throw e }
    private suspend fun ensureStorageWritable() {
        check(_storageIssue.value == null) { storageIssueText }
        // Do not create a replacement key while unreadable encrypted rows already exist.
        guardedRead { db.vlessConfigDao().getAll().firstOrNull()?.toDomain() }
    }

    suspend fun getAllConfigs(): List<VlessConfig> = withContext(Dispatchers.IO) { guardedRead { db.vlessConfigDao().getAll().map { it.toDomain() } } }
    suspend fun getActiveConfig(): VlessConfig? = withContext(Dispatchers.IO) { guardedRead { db.vlessConfigDao().getActiveConfig()?.toDomain() } }
    suspend fun addConfig(config: VlessConfig) = withContext(Dispatchers.IO) { ensureStorageWritable(); db.vlessConfigDao().insertOrUpdate(config.toEntity()) }
    suspend fun addConfigs(configs: List<VlessConfig>) = withContext(Dispatchers.IO) { ensureStorageWritable(); db.vlessConfigDao().insertAll(configs.map { it.toEntity() }) }
    suspend fun updateConfig(config: VlessConfig) = withContext(Dispatchers.IO) { ensureStorageWritable(); db.vlessConfigDao().update(config.toEntity()) }

    suspend fun recordPortCheck(id: String, result: com.vlesscardvpn.domain.LatencyBreakdown) = withContext(Dispatchers.IO) {
        ensureStorageWritable()
        val ping = if (result.success) (if (result.tlsMs > 0) result.tlsMs else result.tcpMs) else -1
        db.vlessConfigDao().recordPortCheck(id, ping, result.tcpMs, result.tlsMs, System.currentTimeMillis())
    }
    suspend fun recordAutoTcpHint(id: String, ping: Int) = withContext(Dispatchers.IO) {
        ensureStorageWritable(); db.vlessConfigDao().recordAutoTcpHint(id, ping)
    }
    suspend fun recordTunnelHealth(id: String, healthy: Boolean, latency: Int) = withContext(Dispatchers.IO) {
        ensureStorageWritable(); db.vlessConfigDao().recordTunnelHealth(id, healthy, latency, System.currentTimeMillis())
    }

    private val subscriptionStore by lazy { SubscriptionStore(context) }
    fun subscriptionSources(): List<String> = subscriptionStore.load()

    suspend fun mergeCandidates(incoming: List<VlessConfig>): Int = withContext(Dispatchers.IO) {
        val existing = getAllConfigs().associateBy(AutoConnectPolicy::identity)
        val unique = incoming.distinctBy(AutoConnectPolicy::identity).take(500)
        addConfigs(unique.map { fresh -> existing[AutoConnectPolicy.identity(fresh)]?.let { old ->
            fresh.copy(id = old.id, isFavorite = old.isFavorite, isActive = old.isActive, addedAt = old.addedAt,
                healthState = old.healthState, httpLatencyMs = old.httpLatencyMs, failureCount = old.failureCount,
                pingMs = old.pingMs, lastCheck = old.lastCheck, source = old.source, isFree = old.isFree)
        } ?: fresh })
        unique.size
    }

    suspend fun importText(text: String): Int {
        require(text.length <= SubscriptionPolicy.MAX_TEXT) { "Импорт больше 2 МиБ" }
        val urls = SubscriptionPolicy.urls(text)
        val nodes = UniversalConfigParser.parseAny(text).toMutableList()
        for (url in urls) nodes += PublicConfigFetcher.importSubscription(url).map { it.copy(source = "subscription", isFree = false) }
        require(nodes.isNotEmpty()) { "Не найдены конфигурации или публичные HTTPS-подписки" }
        // Encrypt/save only after every requested subscription has parsed successfully.
        if (urls.isNotEmpty()) subscriptionStore.add(urls)
        return mergeCandidates(nodes)
    }

    suspend fun setActive(configId: String) = withContext(Dispatchers.IO) {
        db.vlessConfigDao().setActive(configId)
        updateSettings { it.copy(lastWorkingConfigId = configId) }
    }

    suspend fun deleteConfig(id: String) = withContext(Dispatchers.IO) { db.vlessConfigDao().deleteById(id) }
    suspend fun clearFreeNodes() = withContext(Dispatchers.IO) { db.vlessConfigDao().clearFreeNodes() }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        db.vlessConfigDao().toggleFavorite(id)
    }

    suspend fun testAllConfigs() = withContext(Dispatchers.IO) {
        val all = db.vlessConfigDao().getAll()
        // Run parallel ping tests in chunks of 20 with 1500ms timeout for ultra-fast scanning
        all.chunked(20).forEach { chunk ->
            coroutineScope {
                chunk.map { item ->
                    async {
                        val breakdown = PingTester.testDetailedLatency(item.toDomain(), timeoutMs = 1500)
                        recordPortCheck(item.id, breakdown)
                    }
                }.awaitAll()
            }
        }
    }

    fun getDatabase(): AppDatabase = db

    // The database is a process-wide singleton shared with VlessVpnService and
    // SubscriptionUpdateWorker — closing it here would break them. Lifecycle is
    // owned by the process, so close() is intentionally a no-op.
    fun close() { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }

    fun saveRescueProfile(profile: com.vlesscardvpn.domain.RescueProfile) {
        rescuePrefs.edit().putString("rescue_${profile.configId}", profile.toJson()).apply()
    }

    fun getRescueProfile(configId: String): com.vlesscardvpn.domain.RescueProfile? {
        val raw = rescuePrefs.getString("rescue_$configId", null) ?: return null
        return com.vlesscardvpn.domain.RescueProfile.fromJson(raw)
    }

    fun getAllRescueProfiles(): List<com.vlesscardvpn.domain.RescueProfile> = rescuePrefs.all.values.mapNotNull {
        if (it is String) com.vlesscardvpn.domain.RescueProfile.fromJson(it) else null
    }

    fun clearRescueProfile(configId: String) { rescuePrefs.edit().remove("rescue_$configId").apply() }

    fun recordPassportCheck(configId: String, isSuccess: Boolean, latencyMs: Int, method: String) {
        val countKey = "checks_count_$configId"
        val successKey = "checks_success_$configId"
        val total = passportPrefs.getInt(countKey, 0) + 1
        val succ = passportPrefs.getInt(successKey, 0) + if (isSuccess) 1 else 0
        passportPrefs.edit()
            .putInt(countKey, total)
            .putInt(successKey, succ)
            .putLong("last_check_$configId", System.currentTimeMillis())
            .putInt("last_latency_$configId", latencyMs)
            .apply()
    }

    fun getPassportStats(configId: String): Pair<Int, Int> = Pair(
        passportPrefs.getInt("checks_success_$configId", 0),
        passportPrefs.getInt("checks_count_$configId", 0)
    )

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settingsFlow.value)
        _settingsFlow.value = updated
        saveSettingsToPrefs(updated)
    }

    private fun loadSettingsFromPrefs() = AppSettings(
        isDarkTheme = prefs.getBoolean("isDarkTheme", true),
        themeMode = com.vlesscardvpn.domain.AppAppearance.normalize(prefs.getString("themeMode", "system") ?: "system"),
        autoSelect = prefs.getBoolean("autoSelect", false),
        autoSelectBestPing = prefs.getBoolean("autoSelectBestPing", true),
        healthCheckInterval = prefs.getInt("healthCheckInterval", 30),
        failoverEnabled = prefs.getBoolean("failoverEnabled", true),
        lastWorkingConfigId = prefs.getString("lastWorkingConfigId", "") ?: "",
        enableRuDirect = prefs.getBoolean("enableRuDirect", true),
        blockQuicYouTube = prefs.getBoolean("blockQuicYouTube", true),
        customSniOverride = prefs.getString("customSniOverride", "auto") ?: "auto",
        customDnsProvider = prefs.getString("customDnsProvider", "Cloudflare (1.1.1.1)") ?: "Cloudflare (1.1.1.1)",
        mtuSize = prefs.getInt("mtuSize", 1400),
        autoReconnectOnNetworkChange = prefs.getBoolean("autoReconnectOnNetworkChange", true),
        autoTestAfterImport = prefs.getBoolean("autoTestAfterImport", true),
        includeFreeNodesInMainList = prefs.getBoolean("includeFreeNodesInMainList", true),
        showOnlyWorkingNodes = prefs.getBoolean("showOnlyWorkingNodes", false),
        deduplicateNodes = prefs.getBoolean("deduplicateNodes", true),
        maxFreeNodesToAdd = prefs.getInt("maxFreeNodesToAdd", 100),
        autopilotConsentGiven = prefs.getBoolean("autopilotConsentGiven", false),
        autopilotAllowedOnlyFavorites = prefs.getBoolean("autopilotAllowedOnlyFavorites", false),
        autopilotAggressiveAdapting = prefs.getBoolean("autopilotAggressiveAdapting", false),
        githubIssuesRepo = prefs.getString("githubIssuesRepo", "vless-card-vpn/vless-card-vpn") ?: "vless-card-vpn/vless-card-vpn",
        githubApiToken = prefs.getString("githubApiToken", "") ?: "",
        autoSendCrashReportsConsent = prefs.getBoolean("autoSendCrashReportsConsent", false),
        vpnCore = prefs.getString("vpnCore", "auto") ?: "auto",
        evasionStrategy = prefs.getString("evasionStrategy", "auto_cascade") ?: "auto_cascade",
        enableSniRotation = prefs.getBoolean("enableSniRotation", true),
        enableFragmentation = prefs.getBoolean("enableFragmentation", true),
        fragmentPackets = prefs.getString("fragmentPackets", "tlshello") ?: "tlshello",
        fragmentInterval = prefs.getString("fragmentInterval", "5-15ms") ?: "5-15ms"
    )

    private fun saveSettingsToPrefs(settings: AppSettings) {
        prefs.edit().apply {
            putBoolean("isDarkTheme", settings.isDarkTheme)
            putString("themeMode", com.vlesscardvpn.domain.AppAppearance.normalize(settings.themeMode))
            putBoolean("autoSelect", settings.autoSelect)
            putBoolean("autoSelectBestPing", settings.autoSelectBestPing)
            putInt("healthCheckInterval", settings.healthCheckInterval)
            putBoolean("failoverEnabled", settings.failoverEnabled)
            putString("lastWorkingConfigId", settings.lastWorkingConfigId)
            putBoolean("enableRuDirect", settings.enableRuDirect)
            putBoolean("blockQuicYouTube", settings.blockQuicYouTube)
            putString("customSniOverride", settings.customSniOverride)
            putString("customDnsProvider", settings.customDnsProvider)
            putInt("mtuSize", settings.mtuSize)
            putBoolean("autoReconnectOnNetworkChange", settings.autoReconnectOnNetworkChange)
            putBoolean("autoTestAfterImport", settings.autoTestAfterImport)
            putBoolean("includeFreeNodesInMainList", settings.includeFreeNodesInMainList)
            putBoolean("showOnlyWorkingNodes", settings.showOnlyWorkingNodes)
            putBoolean("deduplicateNodes", settings.deduplicateNodes)
            putInt("maxFreeNodesToAdd", settings.maxFreeNodesToAdd)
            putBoolean("autopilotConsentGiven", settings.autopilotConsentGiven)
            putBoolean("autopilotAllowedOnlyFavorites", settings.autopilotAllowedOnlyFavorites)
            putBoolean("autopilotAggressiveAdapting", settings.autopilotAggressiveAdapting)
            putString("githubIssuesRepo", settings.githubIssuesRepo)
            putString("githubApiToken", settings.githubApiToken)
            putBoolean("autoSendCrashReportsConsent", settings.autoSendCrashReportsConsent)
            putString("vpnCore", settings.vpnCore)
            putString("evasionStrategy", settings.evasionStrategy)
            putBoolean("enableSniRotation", settings.enableSniRotation)
            putBoolean("enableFragmentation", settings.enableFragmentation)
            putString("fragmentPackets", settings.fragmentPackets)
            putString("fragmentInterval", settings.fragmentInterval)
            apply()
        }
    }
}
