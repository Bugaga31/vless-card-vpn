package com.vlesscardvpn.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.vlesscardvpn.core.*
import com.vlesscardvpn.model.Mode
import com.vlesscardvpn.vpn.TunnelService

@Composable
fun ConnectionTools() {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val settings by Store.settings.collectAsState()
    val snapshot by ConnectionPassport.state.collectAsState()
    val hold by SessionHold.until.collectAsState()
    val running by Actions.running.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(owner,hold) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(true) { tick++; kotlinx.coroutines.delay(5000) }
        }
    }
    val paused=remember(hold,tick) { SessionHold.active() }
    Column(Modifier.fillMaxWidth().padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick={TunnelService.repairTelegram(context)},enabled=!running && settings.mode==Mode.AUTO && !settings.torAll,
                label={Text("Починить Telegram")})
            AssistChip(onClick={if(paused) SessionHold.clear() else SessionHold.start()},
                label={Text(if(paused) "Вернуть фоновое ускорение" else "Без фонового ускорения 30 мин")})
            AssistChip(onClick={expanded=!expanded},label={Text(if(expanded) "Скрыть паспорт" else "Паспорт соединения")})
        }
        if(paused) Text("Фоновое ускорение на паузе: ещё ${SessionHold.minutesLeft()} мин. VPN и аварийное восстановление не выключены.",style=MaterialTheme.typography.bodySmall)
        if(expanded) {
            Text("Что подтвердили локальные пробы",style=MaterialTheme.typography.titleSmall)
            Text("HTTPS: ${ConnectionPassport.label(snapshot.https)} · DNS: ${ConnectionPassport.label(snapshot.dns)}",style=MaterialTheme.typography.bodySmall)
            Text("Telegram MTProto: ${ConnectionPassport.label(snapshot.telegram)}",style=MaterialTheme.typography.bodySmall)
            Text("Видео YouTube: ${ConnectionPassport.label(snapshot.youtube)}",style=MaterialTheme.typography.bodySmall)
            Text("Это пробы через локальный SOCKS, не проверка вашего аккаунта, звонков или всего пути приложения через TUN. Замеры старше 90 с помечаются устаревшими.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={
                val report=ConnectionPassport.report()
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"Отправить паспорт без адресов и ключей"))
            }) {Text("Поделиться без адресов и ключей")}
        }
    }
}
