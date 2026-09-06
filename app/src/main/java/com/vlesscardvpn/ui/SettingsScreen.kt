package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.ui.theme.*

/**
 * Precision Settings Screen:
 * Structured into clean engineering groups:
 * 1. Подключение (Auto-connect, MTU, Auto-reconnect)
 * 2. Маршрутизация (RU direct, Block QUIC, SNI override)
 * 3. Диагностика (DoH Provider, Latency tests)
 * 4. Приложение (Display filters, pool limits)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: AppRepository,
    onBack: () -> Unit
) {
    val settings by repo.settingsFlow.collectAsState()
    var customSni by remember(settings) { mutableStateOf(settings.customSniOverride) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Параметры связи",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = InstrumentDimens.space16, vertical = InstrumentDimens.space12)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space16)
        ) {
            // Group 1: Подключение & Ядро
            GroupCard(title = "1. ПОДКЛЮЧЕНИЕ И ЯДРО") {
                SettingSwitchItem(
                    title = "Автовыбор лучшего узла",
                    description = "Автоматически подключает сервер с наименьшей задержкой",
                    checked = settings.autoSelect
                ) { repo.updateSettings { s -> s.copy(autoSelect = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                SettingSwitchItem(
                    title = "Авто-восстановление сети",
                    description = "Перезапуск туннеля при переходе между Wi-Fi и мобильной сетью",
                    checked = settings.autoReconnectOnNetworkChange
                ) { repo.updateSettings { s -> s.copy(autoReconnectOnNetworkChange = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                OutlinedTextField(
                    value = settings.mtuSize.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.coerceIn(1280, 1500)?.let {
                            repo.updateSettings { s -> s.copy(mtuSize = it) }
                        }
                    },
                    label = { Text("Размер MTU (по умолчанию 1400)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SignalOrange,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            // Group 2: Маршрутизация & ТСПУ
            GroupCard(title = "2. МАРШРУТИЗАЦИЯ И ОБХОД БЛОКИРОВОК") {
                SettingSwitchItem(
                    title = "Прямой доступ для РФ (.RU)",
                    description = "Госуслуги, банки, Ozon, WB и .ru сайты идут напрямую с нулевой задержкой",
                    checked = settings.enableRuDirect
                ) { repo.updateSettings { s -> s.copy(enableRuDirect = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                SettingSwitchItem(
                    title = "Блокировка QUIC (Ускорение YouTube)",
                    description = "Сброс UDP 443 для предотвращения троттлинга видеопотока",
                    checked = settings.blockQuicYouTube
                ) { repo.updateSettings { s -> s.copy(blockQuicYouTube = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                OutlinedTextField(
                    value = customSni,
                    onValueChange = {
                        customSni = it
                        repo.updateSettings { s -> s.copy(customSniOverride = it) }
                    },
                    label = { Text("Пользовательский SNI маскировки ('auto')") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SignalOrange,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            // Group 3: Диагностика & Безопасность
            GroupCard(title = "3. ДИАГНОСТИКА И DNS") {
                Text(
                    text = "Провайдер DNS-over-HTTPS (DoH)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
                ) {
                    listOf("Cloudflare (1.1.1.1)", "Google (8.8.8.8)", "Yandex (77.88.8.8)").forEach { dns ->
                        FilterChip(
                            selected = settings.customDnsProvider == dns,
                            onClick = { repo.updateSettings { s -> s.copy(customDnsProvider = dns) } },
                            label = { Text(dns.substringBefore(" "), style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SignalOrangeContainer,
                                selectedLabelColor = SignalOrangeContent
                            )
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                SettingSwitchItem(
                    title = "Проверка пинга после импорта",
                    description = "Автоматический замер TCP/TLS латентности добавленных серверов",
                    checked = settings.autoTestAfterImport
                ) { repo.updateSettings { s -> s.copy(autoTestAfterImport = it) } }
            }

            // Group 4: Приложение & Списки
            GroupCard(title = "4. ПРИЛОЖЕНИЕ И ХРАНИЛИЩЕ") {
                SettingSwitchItem(
                    title = "Скрывать недоступные серверы",
                    description = "Отображать только узлы с подтверждённым откликом",
                    checked = settings.showOnlyWorkingNodes
                ) { repo.updateSettings { s -> s.copy(showOnlyWorkingNodes = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                OutlinedTextField(
                    value = settings.maxFreeNodesToAdd.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.coerceIn(1, 300)?.let {
                            repo.updateSettings { s -> s.copy(maxFreeNodesToAdd = it) }
                        }
                    },
                    label = { Text("Лимит импорта публичных узлов") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SignalOrange,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            }

            Spacer(modifier = Modifier.height(InstrumentDimens.space24))
        }
    }
}

@Composable
private fun GroupCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(bottom = InstrumentDimens.space8)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(InstrumentDimens.space16),
                verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space12),
                content = content
            )
        }
    }
}

@Composable
private fun SettingSwitchItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(InstrumentDimens.space12))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SignalOrange,
                checkedTrackColor = SignalOrangeContainer
            )
        )
    }
}
