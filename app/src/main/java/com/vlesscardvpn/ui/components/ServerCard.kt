package com.vlesscardvpn.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.theme.*

@Composable
fun ServerCard(
    config: VlessConfig,
    onConnect: (VlessConfig) -> Unit,
    onPing: (VlessConfig) -> Unit,
    onDelete: (VlessConfig) -> Unit,
    onToggleFavorite: (VlessConfig) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val borderColor = when {
        config.isActive -> NeonCyan
        config.isFavorite -> NeonAmber.copy(alpha = 0.6f)
        config.pingMs in 1..150 -> NeonGreen.copy(alpha = 0.35f)
        config.pingMs > 0 -> DarkBorder
        else -> DarkBorder.copy(alpha = 0.4f)
    }

    val cardBg = if (config.isActive) DarkSurfaceVariant else DarkSurface

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable { onConnect(config) },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(if (config.isActive) 1.5.dp else 1.dp, borderColor),
        color = cardBg,
        tonalElevation = if (config.isActive) 4.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Status Indicator & Name / Address
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                config.isActive -> NeonCyan
                                config.healthState == "HEALTHY" || config.pingMs in 1..180 -> NeonGreen
                                config.healthState == "DEGRADED" || config.pingMs > 180 -> NeonAmber
                                config.healthState == "DEAD" || config.pingMs == -1 -> Color(0xFF6B7280)
                                else -> NeonRed
                            }
                        )
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = config.name.ifBlank { "${config.protocolType.uppercase()} Node" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (config.isActive) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (config.isActive) NeonCyan else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        // Compact Protocol Badge
                        Text(
                            text = config.protocolType.uppercase(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (config.protocolType.lowercase()) {
                                "vless" -> NeonCyan
                                "vmess" -> NeonPurple
                                "trojan" -> NeonAmber
                                else -> NeonGreen
                            },
                            modifier = Modifier
                                .background(DarkBackground, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${config.address}:${config.port}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (config.sni.isNotBlank()) {
                            Text(
                                text = "• ${config.sni}",
                                fontSize = 11.sp,
                                color = TextTertiary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right Actions: Favorite, Ping, Connect
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Ping Badge
                val (pingColor, pingText) = when {
                    config.pingMs in 1..120 -> Pair(NeonGreen, "${config.pingMs}ms")
                    config.pingMs in 121..350 -> Pair(NeonAmber, "${config.pingMs}ms")
                    config.pingMs > 350 -> Pair(NeonRed, "${config.pingMs}ms")
                    config.pingMs == 0 -> Pair(NeonCyan, "wait")
                    else -> Pair(TextTertiary, "—")
                }

                Surface(
                    color = pingColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable { onPing(config) }
                ) {
                    Text(
                        text = pingText,
                        color = pingColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                // Favorite button
                IconButton(
                    onClick = { onToggleFavorite(config) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        if (config.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (config.isFavorite) NeonAmber else TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Connect / Disconnect button
                IconButton(
                    onClick = { onConnect(config) },
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            if (config.isActive) NeonRed.copy(alpha = 0.2f) else NeonCyan.copy(alpha = 0.15f),
                            CircleShape
                        )
                ) {
                    Icon(
                        if (config.isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (config.isActive) "Disconnect" else "Connect",
                        tint = if (config.isActive) NeonRed else NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
