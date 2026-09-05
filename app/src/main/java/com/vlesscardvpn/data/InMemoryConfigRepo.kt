package com.vlesscardvpn.data

import com.vlesscardvpn.domain.VlessConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryConfigRepo {
    private val _configs = MutableStateFlow<List<VlessConfig>>(emptyList())
    val configs: Flow<List<VlessConfig>> = _configs.asStateFlow()

    init {
        // Seed some example
        _configs.value = listOf(
            VlessConfig(name = "Demo Server", address = "demo.vless.example", port = 443, uuid = "demo-uuid", publicKey = "demo-pub", remark = "Demo"),
            VlessConfig(name = "Backup", address = "backup.example.net", port = 443, uuid = "backup-uuid", isFree = true)
        )
    }

    fun addConfig(config: VlessConfig) {
        _configs.value = _configs.value + config
    }

    fun updateConfig(updated: VlessConfig) {
        _configs.value = _configs.value.map { if (it.id == updated.id) updated else it }
    }

    fun deleteConfig(id: String) {
        _configs.value = _configs.value.filterNot { it.id == id }
    }

    fun setActive(id: String) {
        _configs.value = _configs.value.map { it.copy(isActive = it.id == id) }
    }

    fun updatePing(id: String, ping: Int) {
        _configs.value = _configs.value.map { if (it.id == id) it.copy(pingMs = ping) else it }
    }
}