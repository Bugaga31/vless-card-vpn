package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StorageProtectionPanel(issue: String? = null) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = c.surface, border = BorderStroke(1.dp, c.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (issue == null) Icons.Default.Lock else Icons.Default.WarningAmber, null,
                    tint = if (issue == null) c.primary else c.error, modifier = Modifier.size(24.dp))
                Text(if (issue == null) "Защита конфигураций" else "Хранилище недоступно",
                    Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(issue ?: "Параметры конфигураций в базе защищены AES-256-GCM. Ключ хранится в Android Keystore.",
                fontSize = 14.sp, lineHeight = 20.sp, color = c.onSurfaceVariant)
            if (issue == null) Text("Это защита на устройстве. Сетевой маршрут проверяется отдельно.",
                fontSize = 14.sp, lineHeight = 20.sp, color = c.onSurfaceVariant)
        }
    }
}
