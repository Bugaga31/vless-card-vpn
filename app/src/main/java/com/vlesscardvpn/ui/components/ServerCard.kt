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
fun ServerCard(config: VlessConfig, isConnected: Boolean = false, isSelected: Boolean = false,
    onSelect: (VlessConfig) -> Unit, onPing: (VlessConfig) -> Unit, onDelete: (VlessConfig) -> Unit,
    onToggleFavorite: (VlessConfig) -> Unit = {}, onOpenPassport: ((VlessConfig) -> Unit)? = null,
    modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    var actions by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val selected = isSelected || config.isActive
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = c.surface,
        border = BorderStroke(1.dp, if (isConnected) c.tertiary else if (selected) c.primary else c.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onSelect(config) }.heightIn(min = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(config.name.ifBlank { "Сервер из подписки" }, fontSize = 16.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.Medium, color = c.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${config.protocolType.uppercase()} · ${config.security.uppercase()}", fontSize = 14.sp,
                        color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onToggleFavorite(config) }, modifier = Modifier.size(48.dp)) {
                    Icon(if (config.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                        if (config.isFavorite) "Убрать из избранного" else "В избранное",
                        tint = if (config.isFavorite) c.secondary else c.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { actions = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.MoreVert, "Действия сервера", tint = c.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                        DropdownMenuItem(text = { Text("Проверить порт") }, onClick = { actions = false; onPing(config) })
                        if (onOpenPassport != null) DropdownMenuItem(text = { Text("Информация") }, onClick = { actions = false; onOpenPassport(config) })
                        DropdownMenuItem(text = { Text("Удалить", color = c.error) }, onClick = { actions = false; delete = true })
                    }
                }
            }
            Text("${config.address}:${config.port}", fontSize = 14.sp, color = c.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isConnected) "Подключено" else if (selected) "Выбран" else "Сохранён",
                    Modifier.weight(1f), fontSize = 14.sp, color = if (isConnected) c.tertiary else if (selected) c.primary else c.onSurfaceVariant)
                TextButton(onClick = { onPing(config) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (config.pingMs > 0) "TCP · ${config.pingMs} мс" else "Проверить порт", fontSize = 14.sp)
                }
            }
        }
    }
    if (delete) ServerDeleteConfirmation(config.name, onDismiss = { delete = false }, onConfirm = { delete = false; onDelete(config) })
}

@Composable
fun ServerDeleteConfirmation(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Удалить сервер?") },
        text = { Text("${name.ifBlank { "Выбранный сервер" }} будет удалён из списка. Подписка при следующем обновлении может добавить его снова.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Удалить", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

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
                Icon(Icons.Default.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
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
