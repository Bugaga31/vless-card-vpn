package com.vlesscardvpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.theme.*
import com.vlesscardvpn.worker.VpnStatus

/**
 * Precision schematic route diagram: "Устройство → Туннель → Сервер".
 * Accurately reflects real network state without decorative fantasy effects.
 */
@Composable
fun RouteDiagramView(
    vpnStatus: VpnStatus,
    activeConfig: VlessConfig?,
    networkTypeName: String = "Сеть",
    modifier: Modifier = Modifier
) {
    val isConnected = vpnStatus == VpnStatus.CONNECTED
    val isConnecting = vpnStatus == VpnStatus.CONNECTING
    val isError = vpnStatus == VpnStatus.ERROR

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(InstrumentDimens.space16)
        ) {
            // Section title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "СХЕМА МАРШРУТА",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Text(
                    text = when {
                        isConnected -> "МАРШРУТ АКТИВЕН"
                        isConnecting -> "УСТАНОВЛЕНИЕ СВЯЗИ"
                        isError -> "ОШИБКА МАРШРУТА"
                        else -> "НЕ АКТИВЕН"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        isConnected -> SemanticGreen
                        isConnecting -> SignalOrange
                        isError -> SemanticRed
                        else -> GraphiteTertiary
                    }
                )
            }

            Spacer(modifier = Modifier.height(InstrumentDimens.space16))

            // 3-Stage Schematic Nodes: [Device] ── [Tunnel] ── [Server]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Node 1: Device
                RouteNode(
                    icon = Icons.Default.PhoneAndroid,
                    label = "Устройство",
                    subtext = networkTypeName,
                    isActive = true,
                    statusColor = SemanticGreen,
                    modifier = Modifier.weight(1f)
                )

                // Connector 1: Device to Tunnel
                RouteConnector(
                    isActive = isConnected || isConnecting,
                    isWarning = isError,
                    modifier = Modifier.weight(0.5f)
                )

                // Node 2: Tunnel (sing-box Core)
                RouteNode(
                    icon = Icons.Default.Router,
                    label = "Туннель",
                    subtext = when {
                        isConnected -> "MTU 1400"
                        isConnecting -> "Запуск..."
                        isError -> "Сбой"
                        else -> "Остановлен"
                    },
                    isActive = isConnected,
                    statusColor = when {
                        isConnected -> SemanticGreen
                        isConnecting -> SignalOrange
                        isError -> SemanticRed
                        else -> SemanticNeutral
                    },
                    modifier = Modifier.weight(1f)
                )

                // Connector 2: Tunnel to Server
                RouteConnector(
                    isActive = isConnected,
                    isWarning = isError || isConnecting,
                    modifier = Modifier.weight(0.5f)
                )

                // Node 3: Target Proxy Server
                RouteNode(
                    icon = Icons.Default.Dns,
                    label = "Сервер",
                    subtext = if (activeConfig != null) {
                        if (activeConfig.pingMs > 0) "${activeConfig.pingMs} мс" else activeConfig.protocolType.uppercase()
                    } else "Не выбран",
                    isActive = isConnected,
                    statusColor = when {
                        isConnected -> SemanticGreen
                        activeConfig != null && activeConfig.pingMs in 1..200 -> SemanticGreen
                        activeConfig != null && activeConfig.pingMs > 200 -> SemanticAmber
                        isError -> SemanticRed
                        else -> SemanticNeutral
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RouteNode(
    icon: ImageVector,
    label: String,
    subtext: String,
    isActive: Boolean,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isActive) statusColor.copy(alpha = 0.12f) else MineralSurfaceSubtle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) statusColor else GraphiteTertiary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.height(InstrumentDimens.space8))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Text(
            text = subtext,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun RouteConnector(
    isActive: Boolean,
    isWarning: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(bottom = 22.dp)
            .height(2.dp)
            .background(
                when {
                    isActive -> SemanticGreen
                    isWarning -> SignalOrange
                    else -> MineralBorder
                }
            )
    )
}
