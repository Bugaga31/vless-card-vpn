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
import androidx.compose.ui.draw.clip
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
    var selectedCount by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("⚡ Free Community Reality Nodes", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = DarkSurface,
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Auto-Scrape GitHub Mirror Pools", fontWeight = FontWeight.Bold, color = NeonCyan)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Scans 15+ community repositories (kort0881, igareck, AvenCores, barry-far, ByeWhiteLists) and tests low-latency working Reality / Vision nodes.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                isLoading = true
                                scope.launch {
                                    val results = PublicConfigFetcher.fetchAllPublicConfigs(context)
                                    fetchedConfigs = results
                                    selectedCount = results.size
                                    isLoading = false
                                }
                            },
                            enabled = !isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF090A0F)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF090A0F), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scanning pools...")
                            } else {
                                Icon(Icons.Default.CloudSync, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Fetch & Test Nodes")
                            }
                        }

                        if (fetchedConfigs.isNotEmpty()) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        repo.addConfigs(fetchedConfigs)
                                        Toast.makeText(context, "Added ${fetchedConfigs.size} servers to your list", Toast.LENGTH_SHORT).show()
                                        onBack()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color(0xFF090A0F)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.DownloadDone, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add All (${fetchedConfigs.size})")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (fetchedConfigs.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tap 'Fetch & Test Nodes' to scrape live public servers.", color = TextTertiary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(fetchedConfigs, key = { it.id }) { config ->
                        ServerCard(
                            config = config,
                            onConnect = {
                                scope.launch {
                                    repo.addConfig(config)
                                    repo.setActive(config.id)
                                    Toast.makeText(context, "Node saved & set active", Toast.LENGTH_SHORT).show()
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
