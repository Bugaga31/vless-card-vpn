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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AppRepository(private val context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "vless_vpn.db"
    ).fallbackToDestructiveMigration().build()

    private val prefs: SharedPreferences = context.getSharedPreferences("vless_vpn_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _settingsFlow = MutableStateFlow(loadSettingsFromPrefs())
    val settingsFlow = _settingsFlow.asStateFlow()

    init {
        scope.launch {
            if (db.vlessConfigDao().getAll().isEmpty()) {
                initDefaultConfigs()
            }
        }
    }

    val configsFlow: Flow<List<VlessConfig>> = db.vlessConfigDao().getAllFlow().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun getAllConfigs(): List<VlessConfig> = withContext(Dispatchers.IO) {
        db.vlessConfigDao().getAll().map { it.toDomain() }
    }

    suspend fun getActiveConfig(): VlessConfig? = withContext(Dispatchers.IO) {
        db.vlessConfigDao().getActiveConfig()?.toDomain()
    }

    suspend fun addConfig(config: VlessConfig) = withContext(Dispatchers.IO) {
        db.vlessConfigDao().insertOrUpdate(config.toEntity())
    }

    suspend fun addConfigs(configs: List<VlessConfig>) = withContext(Dispatchers.IO) {
        db.vlessConfigDao().insertAll(configs.map { it.toEntity() })
    }

    suspend fun updateConfig(config: VlessConfig) = withContext(Dispatchers.IO) {
        db.vlessConfigDao().update(config.toEntity())
    }

    suspend fun setActive(configId: String) = withContext(Dispatchers.IO) {
        db.vlessConfigDao().setActive(configId)
        updateSettings { it.copy(lastWorkingConfigId = configId) }
    }

    suspend fun deleteConfig(id: String) = withContext(Dispatchers.IO) {
        db.vlessConfigDao().deleteById(id)
    }

    suspend fun clearFreeNodes() = withContext(Dispatchers.IO) {
        db.vlessConfigDao().clearFreeNodes()
    }

    suspend fun toggleFavorite(id: String) = withContext(Dispatchers.IO) {
        val entity = db.vlessConfigDao().getById(id) ?: return@withContext
        val updated = entity.copy(isFavorite = !entity.isFavorite)
        db.vlessConfigDao().update(updated)
    }

    suspend fun testAllConfigs() = withContext(Dispatchers.IO) {
        val all = db.vlessConfigDao().getAll()
        for (item in all) {
            val domain = item.toDomain()
            val breakdown = PingTester.testDetailedLatency(domain, timeoutMs = 2500)
            val ping = if (breakdown.success) {
                if (breakdown.tlsMs > 0) breakdown.tlsMs else breakdown.tcpMs
            } else -1

            val updated = item.copy(
                pingMs = ping,
                tcpLatencyMs = breakdown.tcpMs,
                tlsLatencyMs = breakdown.tlsMs,
                healthState = if (ping > 0) "HEALTHY" else "DEAD",
                lastCheck = System.currentTimeMillis()
            )
            db.vlessConfigDao().update(updated)
        }
    }

    fun getDatabase(): AppDatabase = db

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = _settingsFlow.value
        val updated = transform(current)
        _settingsFlow.value = updated
        saveSettingsToPrefs(updated)
    }

    private fun loadSettingsFromPrefs(): AppSettings {
        return AppSettings(
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
            autopilotAggressiveAdapting = prefs.getBoolean("autopilotAggressiveAdapting", false)
        )
    }

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
            apply()
        }
    }

    private suspend fun initDefaultConfigs() {
        val defaultNodes = listOf(
            VlessConfig(
                id = "nl-node-01",
                name = "🇳🇱 Amsterdam Stealth Core",
                address = "185.196.10.15",
                port = 443,
                uuid = "b7a5e840-7e61-460d-a7fa-0dc6b6a6742a",
                sni = "yandex.ru",
                publicKey = "Xb2qfC4uJt4Xh3mP9nL8rK1sV5wY0zQ3aE6dG7iO2k=",
                shortId = "6a",
                pingMs = 42,
                country = "Netherlands",
                isFavorite = true
            ),
            VlessConfig(
                id = "de-node-02",
                name = "🇩🇪 Frankfurt Reality Ultra",
                address = "194.36.191.12",
                port = 443,
                uuid = "c8b4f912-4d23-411a-96ea-5fb3d88190bc",
                sni = "vk.com",
                publicKey = "mF4qL9pT2sW5vY8bX1cZ3aD6eH0gJ4kN7rP9uI2oK5=",
                shortId = "1f",
                pingMs = 38,
                country = "Germany",
                isFavorite = true
            ),
            VlessConfig(
                id = "fi-node-03",
                name = "🇫🇮 Helsinki Bypass Turbo",
                address = "95.216.142.88",
                port = 443,
                uuid = "e9a1b2c3-d4e5-4f6a-8b9c-0d1e2f3a4b5c",
                sni = "yandex.ru",
                publicKey = "kL8rN2pT5vX9zB1cD4eG7iJ0mP3sU6wY9bA2dF5hK8=",
                shortId = "4b",
                pingMs = 29,
                country = "Finland"
            )
        )
        db.vlessConfigDao().insertAll(defaultNodes.map { it.toEntity() })
    }
}
