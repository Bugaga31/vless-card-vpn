package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.data.PublicConfigFetcher
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.ui.theme.DarkBackground
import com.vlesscardvpn.ui.theme.TextPrimary
import com.vlesscardvpn.util.UniversalConfigParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeConfigsScreen(
    repo: InMemoryConfigRepo,
    onBack: () -> Unit = {}
) {
    val freeSources = remember { PublicConfigFetcher.DEFAULT_SOURCES }
    var selected by remember { mutableStateOf("") }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Public Node Sources", color = TextPrimary) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text(
                "Config sources automatically scanned by Auto-Parse:",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(freeSources) { sourceUrl ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(sourceUrl, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = selected,
                onValueChange = { selected = it },
                label = { Text("Custom source link or VLESS URI") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (selected.isNotBlank()) {
                        val parsed = UniversalConfigParser.parseAny(selected)
                        if (parsed.isNotEmpty()) {
                            repo.addConfigs(parsed)
                        }
                        selected = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Custom")
            }
        }
    }
}
