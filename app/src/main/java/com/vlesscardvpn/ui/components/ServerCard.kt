package com.vlesscardvpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.domain.VlessConfig

@Composable
fun ServerCard(
    config: VlessConfig,
    onConnect: (VlessConfig) -> Unit,
    onPing: (VlessConfig) -> Unit,
    onDelete: (VlessConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (config.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.name.ifBlank { config.remark },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${config.address}:${config.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                    Text(
                        text = "SNI: ${config.sni} • Flow: ${config.flow}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                if (config.pingMs >= 0) {
                    Text(
                        text = "${config.pingMs}ms",
                        color = when {
                            config.pingMs < 150 -> Color(0xFF4CAF50)
                            config.pingMs < 400 -> Color(0xFFFFC107)
                            else -> Color(0xFFF44336)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onConnect(config) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (config.isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (config.isActive) "Disconnect" else "Connect")
                }

                OutlinedButton(
                    onClick = { onPing(config) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Ping")
                }

                if (config.isFree) {
                    Text(
                        "FREE",
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50)
                    )
                }

                OutlinedButton(
                    onClick = { onDelete(config) }
                ) {
                    Text("×")
                }
            }
        }
    }
}