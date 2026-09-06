package com.vlesscardvpn.ui

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.data.PublicConfigFetcher
import com.vlesscardvpn.ui.theme.*
import com.vlesscardvpn.util.UniversalConfigParser
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeConfigsScreen(
    repo: InMemoryConfigRepo,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsState(initial = com.vlesscardvpn.domain.AppSettings())
    var customSourceInput by remember { mutableStateOf("") }
    val isFetching by repo.isFetching.collectAsState(initial = false)
    val fetchStatus by repo.fetchStatus.collectAsState(initial = "")
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        containerColor = DarkBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Public Node Repositories", fontWeight = FontWeight.Bold, color = TextPrimary) },
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
            // Header Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudSync, contentDescription = null, tint = NeonPurple, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Auto-Scanned Public Sources", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "The parser automatically polls and verifies VLESS/VMess/Trojan nodes from verified open GitHub repositories.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text("ACTIVE SOURCE FEEDS (${settings.autoFetchSources.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NeonPurple)
            Spacer(modifier = Modifier.height(6.dp))

            // Source List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(settings.autoFetchSources) { sourceUrl ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, DarkBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sourceUrl.substringAfterLast("/").ifBlank { sourceUrl },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = sourceUrl,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = {
                                    val updated = settings.autoFetchSources.filterNot { it == sourceUrl }
                                    repo.updateSettings(settings.copy(autoFetchSources = updated))
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = NeonRed.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Add Custom Source Feed
            OutlinedTextField(
                value = customSourceInput,
                onValueChange = { customSourceInput = it },
                label = { Text("Add custom URL feed or VLESS link") },
                placeholder = { Text("https://raw.githubusercontent.com/.../nodes.txt") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonPurple,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val input = customSourceInput.trim()
                        if (input.startsWith("http://") || input.startsWith("https://")) {
                            if (!settings.autoFetchSources.contains(input)) {
                                repo.updateSettings(settings.copy(autoFetchSources = settings.autoFetchSources + input))
                                customSourceInput = ""
                                Toast.makeText(context, "Feed source added", Toast.LENGTH_SHORT).show()
                            }
                        } else if (input.isNotBlank()) {
                            val parsed = UniversalConfigParser.parseAny(input)
                            if (parsed.isNotEmpty()) {
                                repo.addConfigs(parsed)
                                customSourceInput = ""
                                Toast.makeText(context, "Added ${parsed.size} configs directly", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Source / Link", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        scope.launch {
                            repo.setFetching(true, "Scanning sources...")
                            try {
                                val working = PublicConfigFetcher.fetchAndFilterWorkingConfigs(
                                    sources = settings.autoFetchSources
                                )
                                repo.addConfigs(working.take(settings.maxFreeNodesToAdd))
                                snackbarHostState.showSnackbar("Discovered ${working.size} alive nodes")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Fetch failed: ${e.localizedMessage}")
                            } finally {
                                repo.setFetching(false, "")
                            }
                        }
                    },
                    enabled = !isFetching,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color(0xFF090A0F)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(42.dp)
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF090A0F), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Fetch & Test Now", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
