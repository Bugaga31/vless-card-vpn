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
        _configs.value = listOf(
            VlessConfig(
                name = "⚡ Yandex Masked (NL)",
                address = "nl-node1.vless-reality.net",
                port = 443,
                uuid = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
                protocolType = "vless",
                flow = "xtls-rprx-vision",
                security = "reality",
                sni = "yandex.ru",
                publicKey = "k8b3h29dm19kdj02kmd92kd91kd02kmd9102kd91k2d",
                shortId = "1a2b3c",
                remark = "Yandex Masked Fast",
                isFree = false,
                pingMs = 45
            ),
            VlessConfig(
                name = "🔒 VK Cloud Reality (DE)",
                address = "de-node2.vless-reality.net",
                port = 443,
                uuid = "f1e2d3c4-b5a6-9876-5432-10fedcba9876",
                protocolType = "vless",
                flow = "xtls-rprx-vision",
                security = "reality",
                sni = "vk.com",
                publicKey = "x9f4a123bc4567890abcdef1234567890abcdef12",
                shortId = "4d5e6f",
                remark = "VK Masked Reality",
                isFree = false,
                pingMs = 58
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
