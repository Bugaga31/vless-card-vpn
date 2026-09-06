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

/**
 * Precision Field Instrument Server Card:
 * Clean, compact horizontal row with clear typographic hierarchy,
 * active indicator, exact latency display and explicit touch targets.
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Semantic border & surface styling
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
                // Precise status dot
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

                        // Protocol Tag
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
