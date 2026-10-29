package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.CacheMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MaintenanceCard() {
    val ctx=LocalContext.current;val scope=rememberCoroutineScope()
    var preview by remember {mutableStateOf<CacheMaintenance.Preview?>(null)}
    var busy by remember {mutableStateOf(false)};var confirm by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf("")}
    fun size(n:Long)=if(n<1024) "$n Б" else if(n<1024*1024) "%.1f КБ".format(n/1024.0) else "%.1f МБ".format(n/1048576.0)
    if(confirm) AlertDialog(onDismissRequest={if(!busy) confirm=false},
        title={Text("Удалить старые временные файлы?")},
        text={Text("Только кэш VLESS Card старше суток: ${preview?.entries?.size ?: 0} файлов, ${size(preview?.bytes ?: 0)}. Настройки, серверы, ключи, APK обновлений и чужие данные не удаляются.")},
        dismissButton={TextButton(enabled=!busy,onClick={confirm=false}) {Text("Отмена")}},
        confirmButton={TextButton(enabled=!busy,onClick={
            val selected=preview ?: return@TextButton
            busy=true;confirm=false
            scope.launch {
                val cleaned=withContext(Dispatchers.IO) {runCatching {CacheMaintenance.clean(ctx.cacheDir,selected,System.currentTimeMillis())}}
                result=cleaned.fold({"Удалено ${it.deleted} файлов (${size(it.bytes)}). Пропущено изменённых или недоступных: ${it.skipped}."},{"Не удалось очистить кэш. Данные и настройки сохранены."})
                preview=null;busy=false
            }
        }) {Text("Удалить временные файлы")}})
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Обслуживание приложения",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Text("Безопасно и только по вашему запросу",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
            Text("Удаляем только старые временные изображения, логи и промежуточные файлы из собственного кэша. Это не очистка RAM и не ускоритель других приложений.",style=MaterialTheme.typography.bodyMedium)
            OutlinedButton(enabled=!busy,onClick={busy=true;result="";scope.launch {
                val scan=withContext(Dispatchers.IO) {runCatching {CacheMaintenance.preview(ctx.cacheDir,System.currentTimeMillis())}}
                scan.onSuccess {preview=it}.onFailure {preview=null;result="Не удалось проверить кэш"};busy=false
            }},modifier=Modifier.fillMaxWidth()) {Text(if(busy) "Обрабатываем…" else "Проверить временные файлы")}
            preview?.let {p->
                Text(if(p.entries.isEmpty()) "Старых временных файлов не найдено" else "Можно удалить: ${p.entries.size} файлов · ${size(p.bytes)}",style=MaterialTheme.typography.bodyMedium)
                if(p.limited) Text("Анализ ограничен одной порцией. После очистки можно проверить ещё раз.",style=MaterialTheme.typography.bodySmall)
                if(p.entries.isNotEmpty()) Button(enabled=!busy,onClick={confirm=true},modifier=Modifier.fillMaxWidth()) {Text("Просмотреть и удалить")}
            }
            if(result.isNotEmpty()) Text(result,style=MaterialTheme.typography.bodyMedium)
            Text("Свежие файлы и установщики защищены от очистки. Для остальных файлов телефона используйте системные настройки хранилища ниже.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
