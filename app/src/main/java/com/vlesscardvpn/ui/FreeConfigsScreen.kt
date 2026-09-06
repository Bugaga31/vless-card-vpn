package com.vlesscardvpn.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository
import com.vlesscardvpn.data.PublicConfigFetcher
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.components.ServerCard
import com.vlesscardvpn.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Precision Free Community Nodes Screen:
 * Fetch & verify public community mirror pools.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeConfigsScreen(
    repo: AppRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var fetchedConfigs by remember { mutableStateOf<List<VlessConfig>>(emptyList()) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Публичные репозитории",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = MaterialTheme.colorScheme.onBackground)
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
                .padding(horizontal = InstrumentDimens.space16, vertical = InstrumentDimens.space12),
            verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space16)
        ) {
            Surface(
                shape = RoundedCornerShape(InstrumentDimens.radiusMedium),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(InstrumentDimens.space16)) {
                    Text(
                        text = "Опрос открытых зеркал конфигураций",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(InstrumentDimens.space4))
                    Text(
                        text = "Сканирует репозитории сообщества и проверяет доступность узлов Reality / Vision без гарантий стабильности публичных серверов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(InstrumentDimens.space12))

                    Row(horizontalArrangement = Arrangement.spacedBy(InstrumentDimens.space8)) {
                        Button(
                            onClick = {
                                isLoading = true
                                scope.launch {
                                    val results = PublicConfigFetcher.fetchAndFilterWorkingConfigs()
                                    fetchedConfigs = results
                                    isLoading = false
                                }
                            },
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = SignalOrange)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(InstrumentDimens.space8))
                                Text("Проверка пула...")
                            } else {
                                Icon(Icons.Default.CloudSync, contentDescription = null)
                                Spacer(modifier = Modifier.width(InstrumentDimens.space8))
                                Text("Загрузить и проверить")
                            }
                        }

                        if (fetchedConfigs.isNotEmpty()) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        repo.addConfigs(fetchedConfigs)
                                        Toast.makeText(context, "Добавлено серверов: ${fetchedConfigs.size}", Toast.LENGTH_SHORT).show()
                                        onBack()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SemanticGreen)
                            ) {
                                Text("Сохранить все (${fetchedConfigs.size})")
                            }
                        }
                    }
                }
            }

            if (fetchedConfigs.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Нажмите 'Загрузить и проверить' для опроса доступных зеркал.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(InstrumentDimens.space4)
                ) {
                    items(fetchedConfigs, key = { it.id }) { config ->
                        ServerCard(
                            config = config,
                            isConnected = false,
                            isSelected = false,
                            onSelect = {
                                scope.launch {
                                    repo.addConfig(config)
                                    repo.setActive(config.id)
                                    Toast.makeText(context, "Узел сохранён и выбран", Toast.LENGTH_SHORT).show()
                                    onBack()
                                }
                            },
                            onPing = {},
                            onDelete = {
                                fetchedConfigs = fetchedConfigs.filter { it.id != config.id }
                            }
                        )
                    }
                }
            }
        }
    }
}
