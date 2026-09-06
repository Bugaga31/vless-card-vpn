package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.data.PublicConfigFetcher
import com.vlesscardvpn.domain.VlessConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeConfigsScreen(repo: AppRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val settings by repo.settingsFlow.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var configs by remember { mutableStateOf<List<VlessConfig>>(emptyList()) }

    fun fetch() {
        if (loading || saving) return
        loading = true
        error = null
        configs = emptyList()
        scope.launch {
            try {
                configs = PublicConfigFetcher.fetchAndFilterWorkingConfigs(
                    maxWorkingCount = settings.maxFreeNodesToAdd.coerceIn(1, 150)
                )
                searched = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Не удалось загрузить список. Проверьте сеть и повторите попытку."
            } finally {
                loading = false
            }
        }
    }

    fun save(selected: List<VlessConfig>, activate: Boolean) {
        if (saving || loading) return
        saving = true
        error = null
        scope.launch {
            try {
                repo.addConfigs(selected)
                if (activate) repo.setActive(selected.first().id)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Не удалось завершить сохранение. Часть серверов могла сохраниться — проверьте список."
            } finally {
                saving = false
            }
        }
    }

    if (consent) {
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { Text("Загрузить публичные серверы?") },
            text = { Text("Будут загружены сторонние списки и выполнены подключения к адресам серверов. Источники и серверы увидят адрес, с которого идут запросы. Доступность порта не подтверждает работу VPN или надёжность владельца.") },
            confirmButton = { TextButton(onClick = { consent = false; fetch() }) { Text("Загрузить") } },
            dismissButton = { TextButton(onClick = { consent = false }) { Text("Отмена") } }
        )
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(title = { Text("Серверы сообщества") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background))
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = colors.surface,
                    border = BorderStroke(1.dp, colors.outline)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Выбор остаётся за вами", style = MaterialTheme.typography.titleLarge)
                        Text("Публичные узлы — не проверенные партнёры сервиса. Для конфиденциальных задач выбирайте собственный сервер или провайдера, которому доверяете.",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Button(onClick = { consent = true }, enabled = !loading && !saving,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (loading) "Проверяем доступность…" else "Найти публичные серверы")
                        }
                        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.primary,
                            trackColor = colors.surfaceVariant)
                        if (configs.isNotEmpty()) {
                            OutlinedButton(onClick = { save(configs, false) }, enabled = !saving && !loading,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (saving) "Сохраняем…" else "Сохранить найденные (${configs.size})")
                            }
                        }
                    }
                }
            }
            error?.let { message -> item { Text(message, color = colors.error, style = MaterialTheme.typography.bodyMedium) } }
            if (!loading && configs.isEmpty() && error == null) {
                item {
                    Text(if (searched) "Доступные узлы не найдены. Возможно, источники недоступны или проверка портов не прошла."
                        else "Здесь появятся найденные серверы. Ничего не подключится автоматически.",
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(configs, key = { it.id }) { config ->
                Surface(shape = RoundedCornerShape(20.dp), color = colors.surface,
                    border = BorderStroke(1.dp, colors.outline)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(config.name, style = MaterialTheme.typography.titleMedium)
                        Text("${config.address}:${config.port}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text("Отклик порта: ${config.pingMs} мс · VPN не проверен",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        OutlinedButton(onClick = { save(listOf(config), true) }, enabled = !saving && !loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Сохранить и выбрать") }
                        TextButton(onClick = { configs = configs.filterNot { it.id == config.id } }, enabled = !saving && !loading,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("Убрать из результатов") }
                    }
                }
            }
        }
    }
}
