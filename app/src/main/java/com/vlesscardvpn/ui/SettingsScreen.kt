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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: AppRepository,
    onBack: () -> Unit
) {
    val settings by repo.settingsFlow.collectAsState()
    var customSni by remember(settings) { mutableStateOf(settings.customSniOverride) }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Core & Tunnel Settings", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Group 1: Автопилот (Autopilot)
            SectionHeader("1. АВТОПИЛОТ (FAILOVER & SMART ROTATION)", NeonCyan)
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingSwitch(
                        title = "Автоматический выбор узла",
                        description = "Выбирает самый быстрый доступный узел при подключении",
                        checked = settings.autoSelect,
                        accentColor = NeonCyan
                    ) { repo.updateSettings { s -> s.copy(autoSelect = it) } }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    SettingSwitch(
                        title = "Авто-переключение при 3 ошибках",
                        description = "Мгновенно переключает на резервный сервер при 3 подтвержденных потерях пакетов",
                        checked = settings.failoverEnabled,
                        accentColor = NeonCyan
                    ) { repo.updateSettings { s -> s.copy(failoverEnabled = it) } }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    Text("Интервал проверки здоровья туннеля", fontSize = 13.sp, color = TextPrimary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(15, 30, 60).forEach { sec ->
                            FilterChip(
                                selected = settings.healthCheckInterval == sec,
                                onClick = { repo.updateSettings { s -> s.copy(healthCheckInterval = sec) } },
                                label = { Text("${sec} сек", fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Group 2: Сеть (Network)
            SectionHeader("2. СЕТЬ & MTU ОПТИМИЗАЦИЯ", NeonGreen)
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = settings.mtuSize.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.coerceIn(1200, 1500)?.let {
                                repo.updateSettings { s -> s.copy(mtuSize = it) }
                            }
                        },
                        label = { Text("TUN MTU (1400 по умолчанию)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    SettingSwitch(
                        title = "Авто-переподключение при смене сети",
                        description = "Мгновенное восстановление туннеля при переходе с Wi-Fi на LTE/5G",
                        checked = settings.autoReconnectOnNetworkChange,
                        accentColor = NeonGreen
                    ) { repo.updateSettings { s -> s.copy(autoReconnectOnNetworkChange = it) } }
                }
            }

            // Group 3: Маршрутизация (Routing)
            SectionHeader("3. МАРШРУТИЗАЦИЯ & АНТИ-БЛОКИРОВКИ", NeonPurple)
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingSwitch(
                        title = "Прямой доступ для сайтов РФ (.RU)",
                        description = "Госуслуги, Банки, Яндекс и VK идут напрямую без задержки VPN",
                        checked = settings.enableRuDirect,
                        accentColor = NeonPurple
                    ) { repo.updateSettings { s -> s.copy(enableRuDirect = it) } }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    SettingSwitch(
                        title = "Блокировка QUIC (Ускорение YouTube)",
                        description = "Блокирует UDP 443 для обхода ТСПУ троттлинга видео",
                        checked = settings.blockQuicYouTube,
                        accentColor = NeonPurple
                    ) { repo.updateSettings { s -> s.copy(blockQuicYouTube = it) } }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    OutlinedTextField(
                        value = customSni,
                        onValueChange = {
                            customSni = it
                            repo.updateSettings { s -> s.copy(customSniOverride = it) }
                        },
                        label = { Text("Кастомный SNI маскировки (или 'auto')") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonPurple,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            }

            // Group 4: Диагностика (Diagnostics)
            SectionHeader("4. ДИАГНОСТИКА & DNS", NeonAmber)
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("DNS-over-HTTPS провайдер", fontSize = 13.sp, color = TextPrimary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Cloudflare (1.1.1.1)", "Google (8.8.8.8)", "Yandex (77.88.8.8)").forEach { dns ->
                            FilterChip(
                                selected = settings.customDnsProvider == dns,
                                onClick = { repo.updateSettings { s -> s.copy(customDnsProvider = dns) } },
                                label = { Text(dns.substringBefore(" "), fontSize = 11.sp) }
                            )
                        }
                    }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    SettingSwitch(
                        title = "Тестировать пинг после импорта",
                        description = "Автоматический замер TCP/TLS латентности новых серверов",
                        checked = settings.autoTestAfterImport,
                        accentColor = NeonAmber
                    ) { repo.updateSettings { s -> s.copy(autoTestAfterImport = it) } }
                }
            }

            // Group 5: Дополнительно (Additional)
            SectionHeader("5. ДОПОЛНИТЕЛЬНО & ХРАНИЛИЩЕ", NeonMagenta)
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingSwitch(
                        title = "Скрывать недоступные серверы",
                        description = "Отображать только узлы с положительным пингом",
                        checked = settings.showOnlyWorkingNodes,
                        accentColor = NeonMagenta
                    ) { repo.updateSettings { s -> s.copy(showOnlyWorkingNodes = it) } }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    OutlinedTextField(
                        value = settings.maxFreeNodesToAdd.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.coerceIn(1, 300)?.let {
                                repo.updateSettings { s -> s.copy(maxFreeNodesToAdd = it) }
                            }
                        },
                        label = { Text("Лимит добавления бесплатных узлов") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonMagenta,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String, color: Color) {
    Text(
        text = title,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    accentColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 14.sp)
            Text(description, fontSize = 12.sp, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accentColor,
                checkedTrackColor = accentColor.copy(alpha = 0.4f)
            )
        )
    }
}
