package com.vlesscardvpn.ui

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AutoPilotEngine
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Precision Settings Screen («Параметры связи»):
 * Structured into clean engineering groups:
 * 1. Подключение и автопилот (Auto-select, failover policy, MTU, network recovery)
 * 2. Маршрутизация и трафик (RU direct, Block QUIC, SNI override)
 * 3. Диагностика и DNS (DoH Provider, Latency tests, check intervals)
 * 4. Приложение и память (Display filters, pool limits, cache reset)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: AppRepository,
    autoPilotEngine: AutoPilotEngine,
    onNavigateToCrashReports: () -> Unit = {},
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.settingsFlow.collectAsState()
    val storageIssue by repo.storageIssueFlow.collectAsState()
    var customSni by remember(settings) { mutableStateOf(settings.customSniOverride) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Настройки",
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
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space16)
        ) {
            AppearancePanel(settings.themeMode) { mode -> repo.updateSettings { it.copy(themeMode = mode) } }
            StorageProtectionPanel(storageIssue)

            // Group 1: Подключение & Автопилот
            GroupCard(title = "Подключение и автопилот") {
                SettingSwitchItem(
                    title = "Автопилот сети (Автовыбор)",
                    description = "Автоматический выбор стабильного узла и восстановление при сбоях",
                    checked = settings.autoSelect,
                    badge = "в реальном времени"
                ) { repo.updateSettings { s -> s.copy(autoSelect = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                SettingSwitchItem(
                    title = "Резерв только из избранного",
                    description = "Переключать при аварии только на серверы, отмеченные звёздочкой ⭐",
                    checked = settings.autopilotAllowedOnlyFavorites,
                    badge = "безопасный пул"
                ) { repo.updateSettings { s -> s.copy(autopilotAllowedOnlyFavorites = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                SettingSwitchItem(
                    title = "Авто-восстановление сети",
                    description = "Повторное подключение при смене Wi-Fi и мобильного интернета",
                    checked = settings.autoReconnectOnNetworkChange
                ) { repo.updateSettings { s -> s.copy(autoReconnectOnNetworkChange = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Размер MTU",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        BadgeTag(text = "требует рестарта")
                    }
                    Text(
                        text = "1400 по умолчанию. Меньший MTU может помочь в сотовой сети. Применяется при новом подключении.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = settings.mtuSize.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.coerceIn(1280, 1500)?.let {
                                repo.updateSettings { s -> s.copy(mtuSize = it) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }

            // Group 2: Ядро туннелирования
            GroupCard(title = "Ядро туннеля и протоколы") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Высокопроизводительный движок",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Универсальный сетевой стек Sing-Box с поддержкой VLESS Reality, VMess, Trojan, uTLS и защиты от ТСПУ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Group 3: Маршрутизация & Трафик
            GroupCard(title = "Маршрутизация и трафик") {
                SettingSwitchItem(
                    title = "Прямой доступ для РФ (.RU)",
                    description = "Банки, Госуслуги, Ozon, WB и .ru сайты идут в обход туннеля с 0 мс задержки",
                    checked = settings.enableRuDirect,
                    badge = "требует рестарта"
                ) { repo.updateSettings { s -> s.copy(enableRuDirect = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                SettingSwitchItem(
                    title = "Блокировка QUIC (Ускорение YouTube)",
                    description = "Сброс UDP/443 для принудительного переключения на быстрый TCP TLS поток",
                    checked = settings.blockQuicYouTube,
                    badge = "требует рестарта"
                ) { repo.updateSettings { s -> s.copy(blockQuicYouTube = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Пользовательский SNI маскировки",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        BadgeTag(text = "требует рестарта")
                    }
                    Text(
                        text = "Укажите 'auto' для использования SNI из конфигурации сервера или введите свой домен.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = customSni,
                        onValueChange = {
                            customSni = it
                            repo.updateSettings { s -> s.copy(customSniOverride = it) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }

            // Group 4: Диагностика & DNS
            GroupCard(title = "Диагностика и dns") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Провайдер DNS-over-HTTPS (DoH)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        BadgeTag(text = "требует рестарта")
                    }
                    Text(
                        text = "Шифрованные DNS-запросы через активный прокси-туннель",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Интервал опроса туннеля",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
                    ) {
                        listOf(15, 30, 60).forEach { sec ->
                            FilterChip(
                                selected = settings.healthCheckInterval == sec,
                                onClick = { repo.updateSettings { s -> s.copy(healthCheckInterval = sec) } },
                                label = { Text("${sec} сек", style = MaterialTheme.typography.bodySmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                SettingSwitchItem(
                    title = "Замер отклика после импорта",
                    description = "Автоматическая проверка доступности и пинга добавленных узлов",
                    checked = settings.autoTestAfterImport
                ) { repo.updateSettings { s -> s.copy(autoTestAfterImport = it) } }
            }

            // Group 5: Приложение & Хранилище
            GroupCard(title = "Приложение и память") {
                SettingSwitchItem(
                    title = "Скрывать недоступные серверы",
                    description = "Отображать в общем списке только узлы с подтверждённым откликом",
                    checked = settings.showOnlyWorkingNodes
                ) { repo.updateSettings { s -> s.copy(showOnlyWorkingNodes = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                SettingSwitchItem(
                    title = "Дедупликация серверов",
                    description = "Исключать дубликаты адресов и UUID при пакетном импорте",
                    checked = settings.deduplicateNodes
                ) { repo.updateSettings { s -> s.copy(deduplicateNodes = it) } }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                Text("Лимит импорта публичных узлов", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = settings.maxFreeNodesToAdd.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.coerceIn(1, 300)?.let {
                            repo.updateSettings { s -> s.copy(maxFreeNodesToAdd = it) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                OutlinedButton(
                    onClick = {
                        // Reuse the shared engine — creating a fresh AutoPilotEngine here
                        // leaked its CoroutineScope on every tap.
                        autoPilotEngine.resetLearnedMemory()
                        Toast.makeText(context, "Память удачных профилей сброшена", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Сбросить обучение", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }

            // Group 5: Отчеты об ошибках и GitHub
            GroupCard(title = "Отчеты об ошибках и связь с разработчиком") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Репозиторий GitHub для баг-репортов",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Куда направлять отчеты о сбоях (формат: owner/repo или full URL)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = settings.githubIssuesRepo,
                        onValueChange = { repo.updateSettings { s -> s.copy(githubIssuesRepo = it) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                Button(
                    onClick = onNavigateToCrashReports,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Отчёты об ошибках", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
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
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
            modifier = Modifier.padding(bottom = InstrumentDimens.space8)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
    badge: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(2.dp))
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
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
private fun BadgeTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}
