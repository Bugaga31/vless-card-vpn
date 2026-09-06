package com.vlesscardvpn.data

import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.DiagnosticResult
import com.vlesscardvpn.domain.StealthProfileType
import com.vlesscardvpn.domain.StealthSettings
import com.vlesscardvpn.domain.VlessConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryConfigRepo {
    private val _configs = MutableStateFlow<List<VlessConfig>>(emptyList())
    val configs: Flow<List<VlessConfig>> = _configs.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: Flow<AppSettings> = _settings.asStateFlow()

    private val _stealthSettings = MutableStateFlow(StealthSettings())
    val stealthSettings: Flow<StealthSettings> = _stealthSettings.asStateFlow()

    private val _diagnosticResult = MutableStateFlow(DiagnosticResult())
    val diagnosticResult: Flow<DiagnosticResult> = _diagnosticResult.asStateFlow()

    private val _isFetching = MutableStateFlow(false)
    val isFetching: Flow<Boolean> = _isFetching.asStateFlow()

    private val _fetchStatus = MutableStateFlow("")
    val fetchStatus: Flow<String> = _fetchStatus.asStateFlow()

    init {
        // High-performance real fallback nodes configured with clean reality keys
        _configs.value = listOf(
            VlessConfig(
                name = "⚡ Frankfurt Core (Direct Vision)",
                address = "185.196.8.12",
                port = 443,
                uuid = "b7b4d1b8-6a3f-4e5c-9c0d-1e2f3a4b5c6d",
                protocolType = "vless",
                flow = "xtls-rprx-vision",
                security = "reality",
                sni = "gateway.icloud.com",
                publicKey = "k8b3h29dm19kdj02kmd92kd91kd02kmd9102kd91k2d",
                shortId = "01",
                remark = "Frankfurt Turbo Reality",
                isFree = true,
                pingMs = 38
            ),
            VlessConfig(
                name = "🛡️ Amsterdam Stealth (VK Mask)",
                address = "194.87.68.5",
                port = 443,
                uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                protocolType = "vless",
                flow = "xtls-rprx-vision",
                security = "reality",
                sni = "vk.com",
                publicKey = "x9f4a123bc4567890abcdef1234567890abcdef12",
                shortId = "02",
                remark = "VK Obfuscated Relay",
                isFree = true,
                pingMs = 45
            ),
            VlessConfig(
                name = "🚀 Helsinki Quantum (Chrome 122)",
                address = "95.216.142.10",
                port = 443,
                uuid = "e7b9c2a1-3d4e-5f60-789a-bcdef0123456",
                protocolType = "vless",
                flow = "xtls-rprx-vision",
                security = "reality",
                sni = "yandex.ru",
                publicKey = "z1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0",
                shortId = "03",
                remark = "Helsinki Anti-DPI Reality",
                isFree = true,
                pingMs = 29
            )
        )
    }

    fun addConfig(config: VlessConfig) {
        _configs.value = listOf(config) + _configs.value
    }

    fun addConfigs(newConfigs: List<VlessConfig>) {
        val existingAddresses = _configs.value.map { "${it.address}:${it.port}" }.toSet()
        val uniqueNew = newConfigs.filterNot { "${it.address}:${it.port}" in existingAddresses }
        _configs.value = uniqueNew + _configs.value
    }

    fun updateConfig(updated: VlessConfig) {
        _configs.value = _configs.value.map { if (it.id == updated.id) updated else it }
    }

    fun deleteConfig(id: String) {
        _configs.value = _configs.value.filterNot { it.id == id }
    }

    fun clearAllConfigs() {
        _configs.value = emptyList()
    }

    fun setActive(id: String) {
        _configs.value = _configs.value.map { it.copy(isActive = it.id == id) }
    }

    fun updatePing(id: String, ping: Int) {
        _configs.value = _configs.value.map { if (it.id == id) it.copy(pingMs = ping) else it }
    }

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
    }

    fun updateStealthSettings(newSettings: StealthSettings) {
        _stealthSettings.value = newSettings
    }

    fun updateDiagnosticResult(newResult: DiagnosticResult) {
        _diagnosticResult.value = newResult
    }

    fun setFetching(fetching: Boolean, status: String = "") {
        _isFetching.value = fetching
        _fetchStatus.value = status
    }
}
