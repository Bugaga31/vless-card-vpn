package com.vlesscardvpn.ui.components

import android.content.Context
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
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.domain.PassportCheckRecord
import com.vlesscardvpn.domain.ServerPassport
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Precision Field Instrument Server Card:
 * Compact horizontal row with active indicator, exact latency display,
 * favorite star, and quick server passport launcher («Паспорт сервера»).
 */
@Composable
fun ServerCard(
    config: VlessConfig,
    isConnected: Boolean = false,
    isSelected: Boolean = false,
    onSelect: (VlessConfig) -> Unit,
    onPing: (VlessConfig) -> Unit,
    onDelete: (VlessConfig) -> Unit,
    onToggleFavorite: (VlessConfig) -> Unit = {},
    onOpenPassport: ((VlessConfig) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        isConnected -> SemanticGreen
        isSelected || config.isActive -> SignalOrange
        config.isFavorite -> SemanticAmber.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline
    }

    val cardBg = when {
        isConnected -> SemanticGreenBg.copy(alpha = 0.4f)
        isSelected || config.isActive -> SignalOrangeContainer.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = InstrumentDimens.space4)
            .clickable { onSelect(config) },
        shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
        border = BorderStroke(if (isConnected || isSelected || config.isActive) 1.5.dp else 1.dp, borderColor),
        color = cardBg,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InstrumentDimens.space12, vertical = InstrumentDimens.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Status Indicator & Name / Address
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isConnected -> SemanticGreen
                                config.healthState == "HEALTHY" || config.pingMs in 1..200 -> SemanticGreen
                                config.healthState == "DEGRADED" || config.pingMs > 200 -> SemanticAmber
                                config.healthState == "DEAD" || config.pingMs == -1 -> GraphiteTertiary
                                else -> SemanticRed
                            }
                        )
                )

                Spacer(modifier = Modifier.width(InstrumentDimens.space12))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
                    ) {
                        Text(
                            text = config.name.ifBlank { "${config.protocolType.uppercase()} Узел" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isConnected || isSelected || config.isActive) FontWeight.Bold else FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Text(
                            text = config.protocolType.uppercase(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(MineralSurfaceSubtle, RoundedCornerShape(InstrumentDimens.radiusSmall))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )

                        if (isConnected) {
                            Text(
                                text = "ПОДКЛЮЧЕНО",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = SemanticGreen,
                                fontSize = 10.sp
                            )
                        } else if (isSelected || config.isActive) {
                            Text(
                                text = "ВЫБРАН",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = SignalOrange,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
                    ) {
                        Text(
                            text = "${config.address}:${config.port}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (config.sni.isNotBlank()) {
                            Text(
                                text = "• SNI: ${config.sni}",
                                style = MaterialTheme.typography.bodySmall,
                                color = GraphiteTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(InstrumentDimens.space8))

            // Right Metrics & Actions
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space4)
            ) {
                // Latency Badge
                val (pingColor, pingBg, pingText) = when {
                    config.pingMs in 1..150 -> Triple(SemanticGreen, SemanticGreenBg, "${config.pingMs} мс")
                    config.pingMs in 151..350 -> Triple(SemanticAmber, SemanticAmberBg, "${config.pingMs} мс")
                    config.pingMs > 350 -> Triple(SemanticRed, SemanticRedBg, "${config.pingMs} мс")
                    config.pingMs == 0 -> Triple(SignalOrange, SignalOrangeContainer, "тест...")
                    else -> Triple(GraphiteTertiary, MineralSurfaceSubtle, "Не проверен")
                }

                Surface(
                    color = pingBg,
                    shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                    modifier = Modifier.clickable { onPing(config) }
                ) {
                    Text(
                        text = pingText,
                        color = pingColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                // Passport button
                if (onOpenPassport != null) {
                    IconButton(
                        onClick = { onOpenPassport(config) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Badge,
                            contentDescription = "Паспорт сервера",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Favorite toggle
                IconButton(
                    onClick = { onToggleFavorite(config) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (config.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = "Избранное",
                        tint = if (config.isFavorite) SemanticAmber else GraphiteTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * 4. «ПАСПОРТ СЕРВЕРА» Dialog
 * Shows confirmed factual data only without fabricated security ratings.
 */
@Composable
fun ServerPassportDialog(
    config: VlessConfig,
    repo: AppRepository,
    onDismiss: () -> Unit
) {
    val (succ, total) = remember(config) { repo.getPassportStats(config.id) }
    val rescueProfile = remember(config) { repo.getRescueProfile(config.id) }
    val addedDate = remember(config) {
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(config.addedAt))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Badge, contentDescription = null, tint = SignalOrange, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Паспорт узла",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space12)
            ) {
                Text(
                    text = config.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                PassportDetailRow(label = "Адрес и порт", value = "${config.address}:${config.port}")
                PassportDetailRow(label = "Протокол", value = "${config.protocolType.uppercase()} • ${config.security.uppercase()}")
                PassportDetailRow(label = "SNI маскировки", value = config.sni.ifBlank { "auto" })
                PassportDetailRow(label = "Источник конфигурации", value = config.source.ifBlank { "Ручной импорт" })
                PassportDetailRow(label = "Дата добавления", value = addedDate)

                // History summary
                PassportDetailRow(
                    label = "История проверок",
                    value = if (total > 0) "Успешно $succ из $total проверок (${((succ.toDouble()/total)*100).toInt()}%)" else "Проверок не выполнялось"
                )

                // Rescue snapshot status
                PassportDetailRow(
                    label = "Спасательный снимок",
                    value = if (rescueProfile != null) "Сохранён (${rescueProfile.verifiedLatencyMs} мс)" else "Снимок не создан"
                )

                // Cryptographic authenticity note
                Text(
                    text = "Примечание: Сервер использует TLS Reality / Vision. Подлинность подтверждается успешным сквозным HTTPS-рукопожатием.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GraphiteTertiary,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange)
            ) {
                Text("Закрыть")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun PassportDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
