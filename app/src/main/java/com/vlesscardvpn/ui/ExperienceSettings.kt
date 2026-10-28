package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.Store
import com.vlesscardvpn.model.Settings

@Composable
fun ExperienceSettings() {
    val s by Store.settings.collectAsState()
    var before by remember { mutableStateOf<Settings?>(null) }
    var reset by remember { mutableStateOf(false) }
    fun update(f:(Settings)->Settings) { Store.update { it.copy(settings=f(it.settings)) } }
    @Composable fun Option(title:String, detail:String, checked:Boolean, onChange:(Boolean)->Unit) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.titleSmall);Text(detail,style=MaterialTheme.typography.bodySmall) }
            Switch(checked=checked,onCheckedChange=onChange)
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Комфорт и приватность",style=MaterialTheme.typography.titleMedium)
        Option("Меньше движения","Убирает волны, заставку и переходы между вкладками; системный масштаб текста сохраняется.",s.reducedMotion) {v->update {it.copy(reducedMotion=v)}}
        Option("Компактные карточки","Скрывает вторичные строки в списке; результаты и выбор сервера остаются доступны.",s.compactServers) {v->update {it.copy(compactServers=v)}}
        Option("Защита экрана","FLAG_SECURE: запрет скриншотов, записи и превью приложения, насколько это поддерживает Android. Не защита от внешней камеры.",s.secureScreen) {v->update {it.copy(secureScreen=v)}}
        Option("Нейтральные имена","Скрывает имена в списке серверов и маршрут на главной. Адреса в редакторах и настройках не скрываются; это не анонимизация.",s.hideServerNames) {v->update {it.copy(hideServerNames=v)}}
        OutlinedButton(onClick={reset=true}) {Text("Сбросить только оформление")}
        before?.let {previous->TextButton(onClick={
            update {it.copy(theme=previous.theme,reducedMotion=previous.reducedMotion,compactServers=previous.compactServers,
                oceanBackground=previous.oceanBackground,oceanMotion=previous.oceanMotion,welcomeSound=previous.welcomeSound)}
            before=null
        }) {Text("Отменить сброс оформления")}}
    }
    if(reset) AlertDialog(onDismissRequest={reset=false},title={Text("Сбросить оформление?")},
        text={Text("Серверы, подписки, маршрутизация и параметры приватности не изменятся.")},
        confirmButton={TextButton(onClick={before=Store.state.value.settings;update {it.copy(theme="dark",reducedMotion=false,compactServers=false,oceanBackground=false,oceanMotion=false,welcomeSound=false)};reset=false}) {Text("Сбросить")}},
        dismissButton={TextButton(onClick={reset=false}) {Text("Отмена")}})
}
