package com.vlesscardvpn.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.vlesscardvpn.data.db.AppDatabase
import com.vlesscardvpn.data.db.toDomain
import com.vlesscardvpn.data.db.toEntity
import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AppRepository(private val context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "vless_vpn.db"
    ).fallbackToDestructiveMigration().build()

    private val prefs: SharedPreferences = context.getSharedPreferences("vless_vpn_prefs", Context.MODE_PRIVATE)
    private val rescuePrefs: SharedPreferences = context.getSharedPreferences("rescue_profiles_prefs", Context.MODE_PRIVATE)
    private val passportPrefs: SharedPreferences = context.getSharedPreferences("server_passport_prefs", Context.MODE_PRIVATE)
    private val _settingsFlow = MutableStateFlow(loadSettingsFromPrefs())
    val settingsFlow = _settingsFlow.asStateFlow()

    val configsFlow: Flow<List<VlessConfig>> = db.vlessConfigDao().getAllFlow().map { list -> list.map { it.toDomain() } }

    suspend fun getAllConfigs(): List<VlessConfig> = withContext(Dispatchers.IO) { db.vlessConfigDao().getAll().map { it.toDomain() } }
    suspend fun getActiveConfig(): VlessConfig? = withContext(Dispatchers.IO) { db.vlessConfigDao().getActiveConfig()?.toDomain() }
    suspend fun addConfig(config: VlessConfig) = withContext(Dispatchers.IO) { db.vlessConfigDao().insertOrUpdate(config.toEntity()) }
    suspend fun addConfigs(configs: List<VlessConfig>) = withContext(Dispatchers.IO) { db.vlessConfigDao().insertAll(configs.map { it.toEntity() }) }
    suspend fun updateConfig(config: VlessConfig) = withContext(Dispatchers.IO) { db.vlessConfigDao().update(config.toEntity()) }

    suspend fun setActive(configId: String) = withContext(Dispatchers.IO) {
        db.vlessConfigDao().setActive(configId)
        updateSettings { it.copy(lastWorkingConfigId = configId) }
    }

    suspend fun deleteConfig(id: String) = withContext(Dispatchers.IO) { db.vlessConfigDao().deleteById(id) }
    suspend fun clearFreeNodes() = withContext(Dispatchers.IO) { db.vlessConfigDao().clearFreeNodes() }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        val entity = db.vlessConfigDao().getById(id) ?: return@withContext
        db.vlessConfigDao().update(entity.copy(isFavorite = !entity.isFavorite))
    }

    suspend fun testAllConfigs() = withContext(Dispatchers.IO) {
        val all = db.vlessConfigDao().getAll()
        for (item in all) {
            val breakdown = PingTester.testDetailedLatency(item.toDomain(), timeoutMs = 2500)
            val ping = if (breakdown.success) {
                if (breakdown.tlsMs > 0) breakdown.tlsMs else breakdown.tcpMs
            } else -1
            db.vlessConfigDao().update(
                item.copy(
                    pingMs = ping,
                    tcpLatencyMs = breakdown.tcpMs,
                    tlsLatencyMs = breakdown.tlsMs,
                    healthState = if (ping > 0) "HEALTHY" else "DEAD",
                    lastCheck = System.currentTimeMillis()
                )
            )
        }
    }

    fun getDatabase(): AppDatabase = db
    fun close() = db.close()

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
        vpnCore = prefs.getString("vpnCore", "auto") ?: "auto"
    )

    private fun saveSettingsToPrefs(settings: AppSettings) {
        prefs.edit().apply {
            putBoolean("isDarkTheme", settings.isDarkTheme)
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
            apply()
        }
    }
}
