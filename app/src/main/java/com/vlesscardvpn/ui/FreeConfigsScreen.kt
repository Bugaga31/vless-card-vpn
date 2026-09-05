package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.data.InMemoryConfigRepo
import com.vlesscardvpn.domain.SampleConfigs
import com.vlesscardvpn.domain.VlessConfig
import com.vlesscardvpn.util.VlessUriParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeConfigsScreen(
    repo: InMemoryConfigRepo = remember { InMemoryConfigRepo() },
    onBack: () -> Unit = {}
) {
    val freeUris = remember { SampleConfigs.freeExamples }
    var selected by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Free Reality Configs") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Text(
                "Add from example public Reality subs (change as needed):",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn {
                items(freeUris) { uri ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(uri.take(80) + "...", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                val parsed = VlessUriParser.parse(uri)
                                if (parsed != null) {
                                    repo.addConfig(parsed.copy(isFree = true, name = "Free: ${parsed.name}"))
                                }
                            }) {
                                Text("Add to Servers")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = selected,
                onValueChange = { selected = it },
                label = { Text("Or paste custom free VLESS") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (selected.isNotBlank()) {
                        val p = VlessUriParser.parse(selected)
                        if (p != null) repo.addConfig(p.copy(isFree = true))
                        selected = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Add Custom Free")
            }
        }
    }
}