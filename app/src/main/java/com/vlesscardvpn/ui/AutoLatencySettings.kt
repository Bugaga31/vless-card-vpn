package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.*

@Composable
fun AutoLatencySettings() {
    val settings by Store.settings.collectAsState()
    var text by remember(settings.autoLatencyMinMs,settings.autoLatencyMaxMs) {
        mutableStateOf(AutoLatencyRange.text(AutoLatencyRange.safe(settings.autoLatencyMinMs,settings.autoLatencyMaxMs)))
    }
    val parsed = AutoLatencyRange.parse(text)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Допуск задержки в Авто",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(value=text,onValueChange={text=it.take(24)},singleLine=true,
            label={Text("До, мс или диапазон: 100–800")},isError=parsed==null,
            supportingText={Text(if(parsed==null) "Число или диапазон от 1 до 3000 мс; начало не больше конца."
                else "По умолчанию 100–800 мс. Верхняя граница обязательна; ответы быстрее начала диапазона тоже принимаются.")},
            modifier=Modifier.fillMaxWidth())
        Button(onClick={parsed?.let { range -> Store.update {it.copy(settings=it.settings.copy(autoLatencyMinMs=range.min,autoLatencyMaxMs=range.max))} }},enabled=parsed!=null) {Text("Сохранить допуск")}
        Text("Это время ответа HTTPS и Telegram MTProto, не ICMP-пинг. Смена допуска сама по себе не обрывает подключение. Стабильность оценивается по серии проб, не по одному ответу.",style=MaterialTheme.typography.bodySmall)
    }
}
