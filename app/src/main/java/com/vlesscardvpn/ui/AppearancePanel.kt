package com.vlesscardvpn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppearancePanel(mode: String, onModeChange: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Оформление", Modifier.semantics { heading() }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onBackground)
        Surface(color = c.surface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, c.outlineVariant)) {
            Column(Modifier.selectableGroup()) {
                listOf("system" to "Как на устройстве", "light" to "Светлая", "dark" to "Тёмная").forEachIndexed { index, (value, label) ->
                    Row(Modifier.fillMaxWidth().selectable(mode == value, role = Role.RadioButton, onClick = { onModeChange(value) })
                        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontSize = 16.sp, color = c.onSurface)
                        RadioButton(selected = mode == value, onClick = null)
                    }
                    if (index < 2) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = c.outlineVariant)
                }
            }
        }
        Text("Тема меняется без перезапуска VPN.", fontSize = 14.sp, color = c.onSurfaceVariant)
    }
}
