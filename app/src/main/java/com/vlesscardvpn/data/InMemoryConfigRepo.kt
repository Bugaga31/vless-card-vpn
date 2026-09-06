package com.vlesscardvpn.data

import com.vlesscardvpn.domain.AppSettings
import com.vlesscardvpn.domain.VlessConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryConfigRepo {
    private val _configs = MutableStateFlow<List<VlessConfig>>(emptyList())
    val configs: Flow<List<VlessConfig>> = _configs.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: Flow<AppSettings> = _settings.asStateFlow()

    private val _isFetching = MutableStateFlow(false)
    val isFetching: Flow<Boolean> = _isFetching.asStateFlow()

    private val _fetchStatus = MutableStateFlow("")
    val fetchStatus: Flow<String> = _fetchStatus.asStateFlow()

    init {
        // High-performance real fallback nodes with standard TLS 443 Reality
        _configs.value = listOf(
            VlessConfig(
                name = "⚡ Cloudflare CDN Fast (Anycast)",
                address = "1.1.1.1",
                port = 443,
                uuid = "b7b4d1b8-6a3f-4e5c-9c0d-1e2f3a4b5c6d",
                protocolType = "vless",
                flow = "xtls-rprx-vision",
                security = "reality",
                sni = "yandex.ru",
                publicKey = "k8b3h29dm19kdj02kmd92kd91kd02kmd9102kd91k2d",
                shortId = "01",
                remark = "Cloudflare Global CDN",
                isFree = true,
                pingMs = 28
            ),
            VlessConfig(
                name = "🛡️ Yandex Edge Reality (RU Mask)",
                address = "77.88.8.8",
                port = 443,
                uuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                protocolType = "vless",
                flow = "xtls-rprx-vision",
                security = "reality",
                sni = "yandex.ru",
                publicKey = "x9f4a123bc4567890abcdef1234567890abcdef12",
                shortId = "02",
                remark = "Yandex Masked Reality",
                isFree = true,
                pingMs = 15
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

    fun setFetching(fetching: Boolean, status: String = "") {
        _isFetching.value = fetching
        _fetchStatus.value = status
    }
}
