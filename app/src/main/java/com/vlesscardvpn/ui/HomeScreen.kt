package com.vlesscardvpn.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.AutoPilotEngine
import com.vlesscardvpn.domain.AutoPilotStatus
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.components.CyberGlobeMapView
import com.vlesscardvpn.ui.components.CyberParticleBackground
import com.vlesscardvpn.ui.theme.*
import com.vlesscardvpn.worker.VpnSessionStats
import com.vlesscardvpn.worker.VpnStatus
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repo: AppRepository,
    autoPilotEngine: AutoPilotEngine,
    vpnStats: VpnSessionStats,
    onToggleConnect: (VlessConfig?) -> Unit,
    onNavigateToServers: () -> Unit,
    onNavigateToAutopilot: () -> Unit,
    onNavigateToDiagnostic: () -> Unit,
    onPanicTrigger: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configs by repo.configsFlow.collectAsState(initial = emptyList())
    val activeConfig = configs.firstOrNull { it.isActive } ?: configs.firstOrNull()
    val autoPilotState by autoPilotEngine.state.collectAsState()

    var lastTapTime by remember { mutableStateOf(0L) }

    Box(modifier = Modifier.fillMaxSize()) {
        CyberParticleBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Cockpit Header & Panic Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Double tap on logo triggers PANIC EXIT
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastTapTime < 450) {
                            Toast.makeText(context, "⚠️ PANIC TRIGGER ACTIVATED", Toast.LENGTH_SHORT).show()
                            onPanicTrigger()
                        } else {
                            lastTapTime = currentTime
                        }
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(NeonCyan)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VLESS COCKPIT",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp,
                        color = TextPrimary,
                        letterSpacing = 1.5.sp
                    )
                }

                // Quick Diagnostics & Autopilot Pills
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = if (autoPilotState.isEnabled) NeonGreen.copy(alpha = 0.15f) else DarkSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (autoPilotState.isEnabled) NeonGreen.copy(alpha = 0.5f) else DarkBorder),
                        modifier = Modifier.clickable { onNavigateToAutopilot() }
                    ) {
                        Text(
                            text = if (autoPilotState.isEnabled) "⚡ AUTOPILOT ON" else "AUTOPILOT",
                            color = if (autoPilotState.isEnabled) NeonGreen else TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    IconButton(
                        onClick = onNavigateToDiagnostic,
                        modifier = Modifier
                            .size(32.dp)
                            .background(DarkSurface, CircleShape)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = "Diagnostics", tint = NeonCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 3D Globe with Live Traffic Vectors
            CyberGlobeMapView(
                isConnected = vpnStats.status == VpnStatus.CONNECTED,
                sourceNodeName = "User [RU]",
                targetNodeName = activeConfig?.name ?: "Global Gateway",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            )

            // Holographic Speedometer & Core Metrics
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = DarkSurface,
                border = BorderStroke(1.dp, if (vpnStats.status == VpnStatus.CONNECTED) NeonCyan.copy(alpha = 0.4f) else DarkBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetricItem(
                        label = "DOWNLOAD",
                        value = "${formatSpeed(vpnStats.downloadSpeedBps)}",
                        unit = "MB/s",
                        accentColor = NeonCyan
                    )
                    Box(modifier = Modifier.width(1.dp).height(30.dp).background(DarkBorder))
                    MetricItem(
                        label = "UPLOAD",
                        value = "${formatSpeed(vpnStats.uploadSpeedBps)}",
                        unit = "MB/s",
                        accentColor = NeonPurple
                    )
                    Box(modifier = Modifier.width(1.dp).height(30.dp).background(DarkBorder))
                    MetricItem(
                        label = "LATENCY",
                        value = if (activeConfig?.pingMs != null && activeConfig.pingMs > 0) "${activeConfig.pingMs}" else "—",
                        unit = "ms",
                        accentColor = NeonGreen
                    )
                }
            }

            // Central Energy Core (Pulsing Power Core)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(120.dp)
                    .clickable { onToggleConnect(activeConfig) }
            ) {
                // Glow circle
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(
                            when (vpnStats.status) {
                                VpnStatus.CONNECTED -> NeonCyan.copy(alpha = 0.2f)
                                VpnStatus.CONNECTING -> NeonAmber.copy(alpha = 0.2f)
                                VpnStatus.ERROR -> NeonRed.copy(alpha = 0.2f)
                                else -> Color(0xFF1E293B).copy(alpha = 0.5f)
                            }
                        )
                )
                Surface(
                    modifier = Modifier.size(86.dp),
                    shape = CircleShape,
                    color = when (vpnStats.status) {
                        VpnStatus.CONNECTED -> NeonCyan
                        VpnStatus.CONNECTING -> NeonAmber
                        VpnStatus.ERROR -> NeonRed
                        else -> Color(0xFF1E293B)
                    },
                    shadowElevation = 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power",
                            tint = if (vpnStats.status == VpnStatus.DISCONNECTED) TextSecondary else Color(0xFF090A0F),
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }
            }

            // Active Server Selector Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToServers() },
                shape = RoundedCornerShape(14.dp),
                color = DarkSurfaceVariant,
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ACTIVE CORE NODE", fontSize = 10.sp, color = NeonCyan, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = activeConfig?.name ?: "No Node Selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (activeConfig != null) "${activeConfig.address}:${activeConfig.port} • SNI: ${activeConfig.sni}" else "Tap to choose server",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                }
            }

            // Error notice banner
            if (vpnStats.status == VpnStatus.ERROR && vpnStats.errorMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = NeonRed.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, NeonRed.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = NeonRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = vpnStats.errorMessage ?: "Connection Error",
                            fontSize = 12.sp,
                            color = NeonRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, unit: String, accentColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextTertiary, letterSpacing = 0.5.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = accentColor)
            Spacer(modifier = Modifier.width(2.dp))
            Text(unit, fontSize = 10.sp, color = TextSecondary)
        }
    }
}

private fun formatSpeed(bps: Long): String {
    val mbs = bps / (1024.0 * 1024.0)
    return String.format(java.util.Locale.US, "%.1f", mbs)
}
