package com.vlesscardvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
                title = { Text("Settings & Masking", fontWeight = FontWeight.Bold, color = TextPrimary) },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Masking & SNI Spoofing
            Text(
                text = "TRAFFIC MASKING (OBFUSCATION)",
                color = NeonCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "SNI Domain Masking",
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "VLESS Reality mimics real HTTPS handshakes to trusted Russian white-listed services (e.g. Yandex, VK) to bypass DPI deep packet inspection.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = customSni,
                        onValueChange = {
                            customSni = it
                            repo.updateSettings(settings.copy(customSniOverride = it))
                        },
                        label = { Text("Custom SNI Mask (e.g. yandex.ru, vk.com)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = Color(0xFF2E3349),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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

            // Section 2: Split Routing (Russian Direct Access)
            Text(
                text = "SPLIT ROUTING & BYPASS",
                color = NeonPurple,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Direct Access for .RU & Russian Services",
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Gosuslugi, Banking apps, Yandex, VK and *.ru sites work directly without VPN slowdown or blocking.",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.enableRuDirect,
                            onCheckedChange = {
                                repo.updateSettings(settings.copy(enableRuDirect = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.5f))
                        )
                    }
                }
            }

            // Section 3: Performance & Ping
            Text(
                text = "AUTOMATION",
                color = NeonGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-sort by lowest Ping",
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Always shows the fastest, lowest latency servers at the top.",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = settings.autoSelectBestPing,
                            onCheckedChange = {
                                repo.updateSettings(settings.copy(autoSelectBestPing = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonGreen, checkedTrackColor = NeonGreen.copy(alpha = 0.5f))
                        )
                    }
                }
            }
        }
    }
}
