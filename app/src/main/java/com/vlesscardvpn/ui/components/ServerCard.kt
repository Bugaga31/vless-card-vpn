package com.vlesscardvpn.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
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
import com.vlesscardvpn.domain.PingTester
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ServerCard(
    config: VlessConfig,
    onConnect: (VlessConfig) -> Unit,
    onPing: (VlessConfig) -> Unit,
    onDelete: (VlessConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isPingingLocal by remember { mutableStateOf(false) }
    var chainStatus by remember { mutableStateOf<String?>(null) }
    var isCheckingChain by remember { mutableStateOf(false) }

    val borderColor = when {
        config.isActive -> NeonCyan
        config.pingMs in 1..150 -> NeonGreen.copy(alpha = 0.4f)
        config.pingMs > 0 -> DarkBorder
        else -> DarkBorder.copy(alpha = 0.6f)
    }

    val cardBg = if (config.isActive) DarkSurfaceVariant else DarkCardBg

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (config.isActive) 1.5.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = if (config.isActive) 6.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Status Indicator, Server Name, Protocol Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    config.isActive -> NeonCyan
                                    config.pingMs in 1..200 -> NeonGreen
                                    config.pingMs > 200 -> NeonAmber
                                    config.pingMs == -1 -> Color(0xFF6B7280)
                                    else -> NeonRed
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = config.name.ifBlank { "${config.protocolType.uppercase()} Server" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Badges: Protocol & Security
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = when (config.protocolType.lowercase()) {
                            "vless" -> NeonCyan.copy(alpha = 0.15f)
                            "vmess" -> NeonPurple.copy(alpha = 0.15f)
                            "trojan" -> NeonAmber.copy(alpha = 0.15f)
                            else -> NeonGreen.copy(alpha = 0.15f)
                        },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(
                            1.dp,
                            when (config.protocolType.lowercase()) {
                                "vless" -> NeonCyan.copy(alpha = 0.4f)
                                "vmess" -> NeonPurple.copy(alpha = 0.4f)
                                "trojan" -> NeonAmber.copy(alpha = 0.4f)
                                else -> NeonGreen.copy(alpha = 0.4f)
                            }
                        )
                    ) {
                        Text(
                            text = config.protocolType.uppercase(),
                            color = when (config.protocolType.lowercase()) {
                                "vless" -> NeonCyan
                                "vmess" -> NeonPurple
                                "trojan" -> NeonAmber
                                else -> NeonGreen
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }

                    if (config.security.isNotBlank()) {
                        Surface(
                            color = DarkSurface,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, DarkBorder)
                        ) {
                            Text(
                                text = config.security.uppercase(),
                                color = TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Details: Host, Port, SNI
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkBackground.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${config.address}:${config.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (config.sni.isNotBlank()) {
                    Text(
                        text = "SNI: ${config.sni}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NeonCyan.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
            }

            // Chain verification bar
            if (config.isActive || chainStatus != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B101E), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isCheckingChain) "⚡ Testing 204 end-to-end..." else (chainStatus ?: "⚡ Ready for E2E HTTP 204"),
                        fontSize = 11.sp,
                        color = if (chainStatus?.contains("FAIL") == true) NeonRed else NeonGreen,
                        fontWeight = FontWeight.Medium
                    )

                    TextButton(
                        onClick = {
                            isCheckingChain = true
                            scope.launch {
                                val (ok, latency) = PingTester.verifyEndToEndConnection()
                                chainStatus = if (ok) "✅ Handshake 204 OK (${latency}ms)" else "❌ 204 Failed"
                                isCheckingChain = false
                            }
                        },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Verify 204", fontSize = 11.sp, color = NeonCyan)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = DarkBorder.copy(alpha = 0.5f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Actions: Latency Badge, Copy, Refresh, Delete, Connect
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Ping Display Badge
                val (pingColor, pingText) = when {
                    config.pingMs in 1..120 -> Pair(NeonGreen, "${config.pingMs} ms")
                    config.pingMs in 121..350 -> Pair(NeonAmber, "${config.pingMs} ms")
                    config.pingMs > 350 -> Pair(NeonRed, "${config.pingMs} ms")
                    config.pingMs == 0 -> Pair(NeonCyan, "testing...")
                    else -> Pair(TextTertiary, "No Ping")
                }

                Surface(
                    color = pingColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, pingColor.copy(alpha = 0.35f))
                ) {
                    Text(
                        text = "● $pingText",
                        color = pingColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Copy raw link
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("VLESS Config", "${config.protocolType}://${config.uuid}@${config.address}:${config.port}?sni=${config.sni}#${config.name}")
                            cm.setPrimaryClip(clip)
                            Toast.makeText(context, "Config copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy Link",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Re-ping
                    IconButton(
                        onClick = {
                            isPingingLocal = true
                            onPing(config)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Ping",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Delete
                    IconButton(
                        onClick = { onDelete(config) },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = NeonRed.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Connect Toggle Button
                    Button(
                        onClick = { onConnect(config) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (config.isActive) NeonRed else NeonCyan,
                            contentColor = if (config.isActive) Color.White else Color(0xFF090A0F)
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            if (config.isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (config.isActive) "Disconnect" else "Connect",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
