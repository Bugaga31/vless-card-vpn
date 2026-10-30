package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Mode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner

@Composable
fun FoundRoutesCard(onConnectFound:()->Unit) {
    val app by Store.ui.collectAsState()
    val status by Tunnel.status.collectAsState()
    val offer by SlowRouteConsent.offer.collectAsState()
    var confirm by remember {mutableStateOf<SlowRouteConsent.Offer?>(null)}
    val ctx=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val count=remember(app.servers,app.states) {FoundConnectPolicy.count(app.servers,app::state)}
    LaunchedEffect(app.servers,app.states,app.settings.mode,app.settings.torAll) {
        if(app.settings.mode==Mode.AUTO)
            SlowRouteConsent.remember(FoundConnectPolicy.shortlist(app.servers,app::state),0)
    }
    LaunchedEffect(owner,app.settings.mode,app.settings.torAll,status.state) {
        if(app.settings.mode!=Mode.AUTO || status.state==Tunnel.State.CONNECTED) confirm=null
        if(app.settings.mode==Mode.AUTO && status.state!=Tunnel.State.CONNECTED)
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {while(true) {
                SlowRouteConsent.expose()
                kotlinx.coroutines.delay(1000)
            }}
    }
    val automatic=app.settings.mode==Mode.AUTO
    val connected=status.state==Tunnel.State.CONNECTED
    if(automatic) Card(Modifier.fillMaxWidth().padding(vertical=6.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(if(app.settings.torAll) "Найденные VLESS для Tor" else "Уже найденные серверы",style=MaterialTheme.typography.titleMedium)
            Text(when {
                connected->"VPN уже подключён. Для смены маршрута сначала отключитесь."
                app.settings.torAll && count>0->"Найденных VLESS: $count. Подготовлю один upstream и запущу Tor через сохранённые мосты. Это не число готовых Tor-выходов. Готовность цепочки проверяется отдельно; прямого обхода нет."
                count==0->"В «Рабочих» пока нет подходящих для Авто серверов. Запустите проверку или общий поиск."
                else->"В «Рабочих»: $count. Возьму готовый маршрут или перепроверю только найденные — без нового поиска по каталогу. Выбранные сервисы остаются обязательными."
            },style=MaterialTheme.typography.bodySmall)
            Button(onClick={
                val current=Store.state.value;val net=Net.key(ctx)
                val ready=AutoEligibility.servicePool(current.servers,current::state,net,System.currentTimeMillis())
                val candidate=offer
                if(ready.isEmpty() && candidate!=null) confirm=candidate else onConnectFound()
            },enabled=FoundConnectPolicy.enabled(automatic,app.settings.torAll,connected,count),modifier=Modifier.fillMaxWidth()) {
                Text(if(app.settings.torAll) "Подключить найденные → Tor ($count)" else "Подключиться к найденным ($count)")
            }
            Text("Допуск: ${AutoLatencyRange.text(AutoLatencyRange.safe(app.settings.autoLatencyMinMs,app.settings.autoLatencyMaxMs))} мс; быстрее нижней границы тоже подходит.",style=MaterialTheme.typography.bodySmall)
        }
    }
    val pinned=confirm
    if(pinned!=null) AlertDialog(onDismissRequest={confirm=null},title={Text("Подключиться с большей задержкой?")},
        text={Text("Проверенный кандидат: ${pinned.ms} мс при пределе ${pinned.limit} мс. Разрешить превышение только для этого сервера и VPN-сессии? Проваленные сервисы этим не разрешаются.")},
        confirmButton={TextButton(onClick={
            confirm=null
            if(SlowRouteConsent.approve(pinned)) onConnectFound()
            else android.widget.Toast.makeText(ctx,"Результат изменился. Нажмите кнопку для свежей проверки найденных.",android.widget.Toast.LENGTH_LONG).show()
        }) {Text("Всё равно подключиться")}},dismissButton={TextButton(onClick={confirm=null}) {Text("Продолжить поиск")}})
}
