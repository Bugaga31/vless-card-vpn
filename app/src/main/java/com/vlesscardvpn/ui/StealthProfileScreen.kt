package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.data.AppRepository

/** Describe shipped engines; no fictional concealment scores or arbitrary operator SNI presets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StealthProfileScreen(repo: AppRepository, onBack: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    Scaffold(containerColor = c.background, topBar = {
        TopAppBar(title = { Text("Режимы Авто") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = c.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Авто выбирает совместимый профиль после проверки связи. SNI и ключи сервера сохраняются.",
                fontSize = 16.sp, lineHeight = 24.sp, color = c.onSurfaceVariant)
            listOf(
                "Совместимый TLS" to "Параметры из конфигурации сервера. Без случайной подмены доменов и без отключения проверки сертификатов.",
                "TLS-фрагментация" to "Поддерживаемая фрагментация ядра для совместимых TLS-подключений. Не гарантирует обход в любой сети.",
                "VPN + ByeDPI" to "Реальный локальный движок меняет передачу внешнего TCP/TLS-потока. Приложения остаются за зашифрованным VPN-туннелем."
            ).forEach { (title, body) ->
                Surface(color = c.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, c.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                        Text(body, fontSize = 14.sp, lineHeight = 22.sp, color = c.onSurfaceVariant)
                    }
                }
            }
            Text("Tor с мостами и zapret пока не встроены.",
                fontSize = 14.sp, lineHeight = 22.sp, color = c.onSurfaceVariant)
        }
    }
}
