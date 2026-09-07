package com.vlesscardvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.core.CrashLogEntry
import com.vlesscardvpn.core.CrashReportManager
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashReportsScreen(
    repo: AppRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.settingsFlow.collectAsState()

    var crashes by remember { mutableStateOf(CrashReportManager.getSavedCrashes(context)) }
    var selectedCrash by remember { mutableStateOf<CrashLogEntry?>(null) }
    var sendingId by remember { mutableStateOf<String?>(null) }
    var showConsentDialog by remember { mutableStateOf(false) }
    var pendingSendCrash by remember { mutableStateOf<CrashLogEntry?>(null) }

    fun refreshList() {
        crashes = CrashReportManager.getSavedCrashes(context)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Отчеты об ошибках",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Обезличенные журналы сбоев (${crashes.size})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    if (crashes.isNotEmpty()) {
                        IconButton(onClick = {
                            CrashReportManager.clearAllCrashes(context)
                            refreshList()
                            Toast.makeText(context, "Журнал ошибок очищен", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Очистить", tint = SemanticRed)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = InstrumentDimens.space16, vertical = InstrumentDimens.space8)
        ) {
            // Privacy info notice
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                color = MineralSurfaceSubtle,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(InstrumentDimens.space12),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = SignalOrange,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(InstrumentDimens.space12))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Безопасность и приватность",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Все UUID, Reality-ключи, токены и адреса автоматически удаляются из отчетов. Отправка на GitHub происходит только с вашего явного согласия.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(InstrumentDimens.space12))

            // GitHub repository destination card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(InstrumentDimens.space12)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Репозиторий GitHub для баг-репортов",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = SignalOrangeContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "GitHub Issues",
                                style = MaterialTheme.typography.labelSmall,
                                color = SignalOrangeContent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = settings.githubIssuesRepo.ifBlank { "vless-card-vpn/vless-card-vpn" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(InstrumentDimens.space12))

            if (crashes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            tint = SemanticGreen,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(InstrumentDimens.space12))
                        Text(
                            text = "Ошибок не зафиксировано",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Приложение работает штатно и без сбоев.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
                ) {
                    items(crashes, key = { it.id }) { item ->
                        val isSending = sendingId == item.id
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (item.isSent) SemanticGreen else MaterialTheme.colorScheme.outline)
                        ) {
                            Column(
                                modifier = Modifier.padding(InstrumentDimens.space16),
                                verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = if (item.isSent) SemanticGreenBg else SemanticRedBg,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = if (item.isSent) "ОТПРАВЛЕНО" else item.type,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (item.isSent) SemanticGreen else SemanticRed,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Text(
                                        text = item.formattedDate,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Text(
                                    text = item.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    maxLines = 2
                                )

                                Text(
                                    text = "${item.deviceModel} • ${item.androidVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { selectedCrash = item },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(InstrumentDimens.radiusSmall)
                                    ) {
                                        Text("Просмотр", fontSize = 13.sp)
                                    }

                                    Button(
                                        onClick = {
                                            pendingSendCrash = item
                                            showConsentDialog = true
                                        },
                                        enabled = !isSending,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = SignalOrange),
                                        shape = RoundedCornerShape(InstrumentDimens.radiusSmall)
                                    ) {
                                        if (isSending) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                color = Color.White,
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("В GitHub", fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Consent Dialog before sending to GitHub
    if (showConsentDialog && pendingSendCrash != null) {
        val targetCrash = pendingSendCrash!!
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = {
                Text(
                    text = "Отправить отчет разработчику?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Отчет об ошибке будет отправлен в официальный репозиторий GitHub:\n${settings.githubIssuesRepo}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "В отчет включены:\n• Модель устройства и версия Android\n• Стек ошибки и системное сообщение\n\nВсе личные ключи, токены и ссылки полностью вырезаны.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConsentDialog = false
                        val repoUrl = settings.githubIssuesRepo.ifBlank { "vless-card-vpn/vless-card-vpn" }
                        val token = settings.githubApiToken

                        if (token.isNotBlank()) {
                            sendingId = targetCrash.id
                            scope.launch {
                                val result = CrashReportManager.sendReportViaGitHubApi(targetCrash, repoUrl, token)
                                sendingId = null
                                result.onSuccess { url ->
                                    CrashReportManager.markAsSent(context, targetCrash.id)
                                    refreshList()
                                    Toast.makeText(context, "Отчет успешно создан в GitHub Issues!", Toast.LENGTH_LONG).show()
                                }.onFailure { e ->
                                    // Fallback to browser intent
                                    val intent = CrashReportManager.createGitHubIssueIntent(targetCrash, repoUrl)
                                    try {
                                        context.startActivity(intent)
                                        CrashReportManager.markAsSent(context, targetCrash.id)
                                        refreshList()
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Ошибка API: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } else {
                            // Direct Browser Intent with prefilled markdown
                            val intent = CrashReportManager.createGitHubIssueIntent(targetCrash, repoUrl)
                            try {
                                context.startActivity(intent)
                                CrashReportManager.markAsSent(context, targetCrash.id)
                                refreshList()
                                Toast.makeText(context, "Открываем GitHub для публикации баг-репорта...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Не удалось открыть браузер: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalOrange)
                ) {
                    Text("Разрешить и отправить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsentDialog = false }) {
                    Text("Отмена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Detail Crash Inspection Dialog
    if (selectedCrash != null) {
        val detail = selectedCrash!!
        AlertDialog(
            onDismissRequest = { selectedCrash = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Детали отчета",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Crash Report", CrashReportManager.buildMarkdownReport(detail))
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Отчет скопирован в буфер", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать")
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Дата: ${detail.formattedDate}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Устройство: ${detail.deviceModel} (${detail.androidVersion})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Ошибка: ${detail.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = SemanticRed
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        color = MineralBackground,
                        shape = RoundedCornerShape(InstrumentDimens.radiusSmall),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            item {
                                Text(
                                    text = detail.stackTrace.ifBlank { "Нет дополнительного стека вызовов." },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = GraphiteSecondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedCrash = null
                        pendingSendCrash = detail
                        showConsentDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SignalOrange)
                ) {
                    Text("Отправить в GitHub")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedCrash = null }) {
                    Text("Закрыть", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}
