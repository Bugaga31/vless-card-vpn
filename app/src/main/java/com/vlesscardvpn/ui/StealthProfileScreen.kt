package com.vlesscardvpn.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.domain.StealthProfileType
import com.vlesscardvpn.domain.StealthSettings
import com.vlesscardvpn.ui.components.CyberGlobeMapView
import com.vlesscardvpn.ui.components.CyberParticleBackground
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StealthProfileScreen(
    repo: InMemoryConfigRepo,
    onBack: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val stealthSettings by repo.stealthSettings.collectAsState(initial = StealthSettings())
    val snackbarHostState = remember { SnackbarHostState() }

    var customSniInput by remember { mutableStateOf(stealthSettings.customSni) }

    Box(modifier = Modifier.fillMaxSize()) {
        CyberParticleBackground(particleCount = 24)

        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "ФРАКТАЛЬНЫЙ ТУННЕЛЬ & МАСКИРОВКА",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                            color = TextPrimary,
                            fontSize = 15.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface.copy(alpha = 0.85f))
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                // 1. Fractal Tunnel Visual Hologram
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.85f)),
                    border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Hub, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Фрактальный туннель (Multi-Hop Mesh)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 13.sp)
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = NeonGreen.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "Целостность: ${stealthSettings.tunnelIntegrityPercent}%",
                                    color = NeonGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Dynamic Neural Map View
                        CyberGlobeMapView(
                            fragmentationLevel = stealthSettings.fractalFragmentationLevel,
                            isConnected = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Fragmentation Slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Степень дробления: ${stealthSettings.fractalFragmentationLevel}/10",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (stealthSettings.fractalFragmentationLevel > 6) "⚡ Макс. анонимность" else "⚡ Баланс скорости",
                                fontSize = 11.sp,
                                color = NeonCyan
                            )
                        }

                        Slider(
                            value = stealthSettings.fractalFragmentationLevel.toFloat(),
                            onValueChange = {
                                repo.updateStealthSettings(stealthSettings.copy(fractalFragmentationLevel = it.toInt()))
                            },
                            valueRange = 1f..10f,
                            steps = 8,
                            colors = SliderDefaults.colors(
                                thumbColor = NeonCyan,
                                activeTrackColor = NeonCyan,
                                inactiveTrackColor = DarkBorder
                            )
                        )

                        // Shuffle Route Button
                        Button(
                            onClick = {
                                repo.updateStealthSettings(
                                    stealthSettings.copy(
                                        lastRouteShuffleTime = System.currentTimeMillis(),
                                        tunnelIntegrityPercent = (98..100).random()
                                    )
                                )
                                scope.launch {
                                    snackbarHostState.showSnackbar("Маршруты перестроены: новые 5 прыжков активированы")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant, contentColor = NeonCyan),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(38.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Перемешать маршруты туннеля", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Disguise / Stealth Profile Selector
                Text(
                    text = "ПРОФИЛЬ ДОВЕРИЯ (СЕТЕВАЯ МАСКИРОВКА)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonPurple,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(StealthProfileType.values()) { profile ->
                        val isSelected = stealthSettings.activeProfile == profile
                        val profileBorder = if (isSelected) NeonCyan else DarkBorder

                        Card(
                            modifier = Modifier
                                .width(150.dp)
                                .clickable {
                                    repo.updateStealthSettings(stealthSettings.copy(activeProfile = profile))
                                },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface),
                            border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, profileBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = profile.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (isSelected) NeonCyan else TextPrimary
                                    )
                                    Surface(
                                        shape = CircleShape,
                                        color = NeonGreen.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = "${profile.stealthScore}%",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = NeonGreen,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = profile.defaultSni,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = profile.tlsFingerprint,
                                    fontSize = 10.sp,
                                    color = TextTertiary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Profile Details Card
                val activeProfile = stealthSettings.activeProfile
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.9f)),
                    border = BorderStroke(1.dp, DarkBorder)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Параметры маскировки '${activeProfile.title}'",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = activeProfile.description, fontSize = 12.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Пакеты: ${activeProfile.minPacketSize}–${activeProfile.maxPacketSize} B", fontSize = 11.sp, color = TextTertiary, fontFamily = FontFamily.Monospace)
                            Text("ALPN: ${activeProfile.defaultAlpn.joinToString(",")}", fontSize = 11.sp, color = NeonCyan, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Cyber Defense Toggles: Background Noise & Fingerprint Rotation
                Text(
                    text = "ПРОДВИНУТАЯ ЗАЩИТА ОТ ТСПУ / DPI",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Noise Generator
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, DarkBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Шумовой генератор (Фоновый шум)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                            Text("Отправляет ложные HTTP запросы каждые 30-60с (/favicon, /blank.html)", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = stealthSettings.enableNoiseGenerator,
                            onCheckedChange = {
                                repo.updateStealthSettings(stealthSettings.copy(enableNoiseGenerator = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan, checkedTrackColor = NeonCyan.copy(alpha = 0.3f))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Fingerprint Rotation
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, DarkBorder)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Мгновенная смена отпечатка (JA3/TLS)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                            Text("Каждые 8 минут меняет сигнатуру устройства для защиты от слежки", fontSize = 11.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = stealthSettings.enableInstantFingerprintRotation,
                            onCheckedChange = {
                                repo.updateStealthSettings(stealthSettings.copy(enableInstantFingerprintRotation = it))
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = NeonPurple, checkedTrackColor = NeonPurple.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        }
    }
}
