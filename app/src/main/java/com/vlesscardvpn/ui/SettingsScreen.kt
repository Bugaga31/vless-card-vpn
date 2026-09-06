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
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: InMemoryConfigRepo,
    onBack: () -> Unit
) {
    val settings by repo.settings.collectAsState(initial = com.vlesscardvpn.domain.AppSettings())
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
            // Section 1: Masking & Anti-DPI
            SectionHeader("ANTIDPI & REALITY MASKING", NeonCyan)
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "SNI Domain Obfuscation",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "VLESS Reality mimics real HTTPS handshakes to trusted Russian white-listed services to bypass ISP DPI blocking.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = customSni,
                        onValueChange = {
                            customSni = it
                            repo.updateSettings(settings.copy(customSniOverride = it))
                        },
                        label = { Text("Custom SNI Mask") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("yandex.ru", "vk.com", "samsung.com", "apple.com").forEach { domain ->
                            FilterChip(
                                selected = customSni == domain,
                                onClick = {
                                    customSni = domain
                                    repo.updateSettings(settings.copy(customSniOverride = domain))
                                },
                                label = { Text(domain, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // Section 2: Routing & Russian Services
            SectionHeader("ROUTING & BYPASS", NeonPurple)
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingSwitch(
                        title = "Direct Access for Russian Services (.RU)",
                        description = "Gosuslugi, Banking apps, Yandex, VK and *.ru sites work directly with 0 ms VPN overhead.",
                        checked = settings.enableRuDirect,
                        accentColor = NeonPurple
                    ) { repo.updateSettings(settings.copy(enableRuDirect = it)) }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    SettingSwitch(
                        title = "Block QUIC (Fix YouTube & Streaming)",
                        description = "Blocks UDP 443 to prevent ISP throttle on YouTube and force high-speed TCP/TLS stream.",
                        checked = settings.blockQuicYouTube,
                        accentColor = NeonPurple
                    ) { repo.updateSettings(settings.copy(blockQuicYouTube = it)) }
                }
            }

            // Section 3: Tunnel & DNS
            SectionHeader("TUNNEL & PERFORMANCE", NeonAmber)
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Secure DNS Provider",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("Cloudflare (1.1.1.1)", "Quad9 (9.9.9.9)", "Google (8.8.8.8)").forEach { dns ->
                            FilterChip(
                                selected = settings.customDnsProvider == dns,
                                onClick = { repo.updateSettings(settings.copy(customDnsProvider = dns)) },
                                label = { Text(dns.substringBefore(" "), fontSize = 11.sp) }
                            )
                        }
                    }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    OutlinedTextField(
                        value = settings.mtuSize.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.coerceIn(1200, 1500)?.let {
                                repo.updateSettings(settings.copy(mtuSize = it))
                            }
                        },
                        label = { Text("TUN Interface MTU (1200 - 1500)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonAmber,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    SettingSwitch(
                        title = "Auto-Reconnect on Network Switch",
                        description = "Instantly restore tunnel when switching between Wi-Fi and LTE.",
                        checked = settings.autoReconnectOnNetworkChange,
                        accentColor = NeonAmber
                    ) { repo.updateSettings(settings.copy(autoReconnectOnNetworkChange = it)) }
                }
            }

            // Section 4: Auto-Parsing & Server List
            SectionHeader("AUTOPARSE & SERVER LIST", NeonGreen)
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingSwitch(
                        title = "Auto-sort by lowest Ping",
                        description = "Shows the fastest and lowest latency servers at the top of the list.",
                        checked = settings.autoSelectBestPing,
                        accentColor = NeonGreen
                    ) { repo.updateSettings(settings.copy(autoSelectBestPing = it)) }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    SettingSwitch(
                        title = "Test latency after import",
                        description = "Immediately ping imported nodes to verify availability.",
                        checked = settings.autoTestAfterImport,
                        accentColor = NeonGreen
                    ) { repo.updateSettings(settings.copy(autoTestAfterImport = it)) }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    SettingSwitch(
                        title = "Hide dead / unreachable nodes",
                        description = "Display only working servers with positive response.",
                        checked = settings.showOnlyWorkingNodes,
                        accentColor = NeonGreen
                    ) { repo.updateSettings(settings.copy(showOnlyWorkingNodes = it)) }

                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)

                    OutlinedTextField(
                        value = settings.maxFreeNodesToAdd.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.coerceIn(1, 200)?.let {
                                repo.updateSettings(settings.copy(maxFreeNodesToAdd = it))
                            }
                        },
                        label = { Text("Maximum free nodes to add on scan") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen,
                            unfocusedBorderColor = DarkBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
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
