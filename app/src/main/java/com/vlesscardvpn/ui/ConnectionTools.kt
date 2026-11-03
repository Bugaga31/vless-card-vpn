package com.vlesscardvpn.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    val events by ConnectionHistory.events.collectAsState()
    val scheduler by ProbeArbiter.state.collectAsState()
    val hold by SessionHold.until.collectAsState()
    val running by Actions.running.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    var duration by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    fun share(text:String,title:String) {
        runCatching {context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),title))}
            .onFailure {android.widget.Toast.makeText(context,"Не удалось открыть отправку; отчёт никуда не отправлен",android.widget.Toast.LENGTH_LONG).show()}
    }
    LaunchedEffect(owner,hold) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while(true) { tick++; kotlinx.coroutines.delay(5000) }
        }
    }
    val paused=remember(hold,tick) { SessionHold.active() }
    val canRepair=!running && settings.mode==Mode.AUTO && !settings.torAll && !(settings.torRouting && "telegram" in settings.services)
    Column(Modifier.fillMaxWidth().padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick={TunnelService.repairTelegram(context)},enabled=canRepair,label={Text("Починить Telegram")})
            AssistChip(onClick={if(paused) SessionHold.clear() else duration=true},label={Text(if(paused) "Вернуть ускорение" else "Не менять ради скорости")})
            AssistChip(onClick={expanded=!expanded},label={Text(if(expanded) "Скрыть паспорт" else "Паспорт соединения")})
            AssistChip(onClick={history=true},label={Text("История проверок")})
            AssistChip(onClick={help=true},label={Text("Что мешает связи?")})
        }
        if(paused) Text("Фоновое ускорение на паузе: ещё ${SessionHold.minutesLeft()} мин. VPN и аварийное восстановление не выключены.",style=MaterialTheme.typography.bodySmall)
        if(expanded) {
            Text("Что подтвердили локальные пробы",style=MaterialTheme.typography.titleSmall)
            Text("Очередь проверок: ${when(scheduler.active){"foreground"->"ручная/стартовая волна";"background"->"фоновая волна";else->"свободна"}} · ожидают приоритета: ${scheduler.foregroundWaiting}. Последнее ожидание ядра: ${scheduler.lastWaitMs} мс (не задержка сети).",style=MaterialTheme.typography.bodySmall)
            Text("HTTPS: ${ConnectionPassport.label(snapshot.https)} · DNS: ${ConnectionPassport.label(snapshot.dns)}",style=MaterialTheme.typography.bodySmall)
            Text("Telegram MTProto: ${ConnectionPassport.label(snapshot.telegram)}",style=MaterialTheme.typography.bodySmall)
            Text("Видео YouTube: ${ConnectionPassport.label(snapshot.youtube)}",style=MaterialTheme.typography.bodySmall)
            Text("Это пробы через локальный SOCKS, не проверка вашего аккаунта, звонков или всего пути приложения через TUN. Замеры старше 90 с помечаются устаревшими.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={share(ConnectionPassport.report(),"Паспорт без адресов и ключей")}) {Text("Поделиться без адресов и ключей")}
        }
    }
    if(duration) AlertDialog(onDismissRequest={duration=false},title={Text("Пауза фонового ускорения")},text={
        Column {
            Text("Приостановит необязательные сравнения и переключения ради скорости. Сбой связи всё ещё может вызвать аварийное переключение.")
            listOf(15,30,60,120).forEach {minutes->TextButton(onClick={SessionHold.start(minutes);duration=false},modifier=Modifier.fillMaxWidth()) {Text("На $minutes мин")}}
        }
    },confirmButton={TextButton(onClick={duration=false}) {Text("Отмена")}})
    if(history) AlertDialog(onDismissRequest={history=false},title={Text("История локальных проверок")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Только изменения результатов в этом процессе: не журнал всего трафика или переподключений. До 100 записей в памяти; после закрытия процесса исчезают. Нет адресов, имён сетей и ключей.",style=MaterialTheme.typography.bodySmall)
            if(events.isEmpty()) Text("Изменений пока нет. История не запускает новые проверки.")
            events.asReversed().forEach {event->
                val label=when(event.kind){"telegram"->"Telegram MTProto";"youtube"->"YouTube media";"dns"->"DNS SOCKS UDP";else->"HTTPS"}
                val age=((android.os.SystemClock.elapsedRealtime()-event.at).coerceAtLeast(0)/1000)
                Text("$label · ${if(event.result=="PASS") "ответ получен" else "нет ответа"} · $age с назад",style=MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick={share(ConnectionHistory.report(),"История без адресов и ключей")},enabled=events.isNotEmpty()) {Text("Поделиться историей")}
            TextButton(onClick={confirmClear=true},enabled=events.isNotEmpty()) {Text("Удалить историю")}
        }
    },confirmButton={TextButton(onClick={history=false}) {Text("Закрыть")}})
    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("Очистить историю?")},text={Text("Удаляются только записи этой сессии. Серверы, настройки и текущий паспорт не изменяются.")},
        confirmButton={TextButton(onClick={ConnectionHistory.clear();confirmClear=false}) {Text("Удалить")}},dismissButton={TextButton(onClick={confirmClear=false}) {Text("Отмена")}})
    if(help) AlertDialog(onDismissRequest={help=false},title={Text("Подсказки по текущим пробам")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            val https=ConnectionPassport.status(snapshot.https)
            val dns=ConnectionPassport.status(snapshot.dns)
            val tg=ConnectionPassport.status(snapshot.telegram)
            val yt=ConnectionPassport.status(snapshot.youtube)
            Text("Это подсказки по наблюдениям, не установленная причина. Дополнительные запросы и изменения настроек не запускаются.",style=MaterialTheme.typography.bodySmall)
            if(https=="FAIL") Text("HTTPS не ответил: возможна проблема сервера, сети или маски. Попробуйте другой подтверждённый сервер; один неудачный ответ не доказывает блокировку.")
            if(dns=="FAIL") Text("DNS через SOCKS UDP не ответил. Не отключайте защиту вслепую: проверьте другой сервер и настройку DNS.")
            if(tg=="FAIL") Text("Проба Telegram MTProto не прошла. Кнопка «Починить Telegram» проверит альтернативу в обычном Авто; авторизацию и звонки это не проверяет.")
            if(yt=="FAIL") Text("YouTube не ответил ожидаемым HTTP 204 через этот маршрут. Проверяется веб-доступ, не загрузка видео; подробности в диагностике.")
            if(listOf(https,dns,tg,yt).any{it=="UNKNOWN" || it=="STALE"}) Text("Есть неизвестные или устаревшие результаты. Они не считаются успехом или отказом. Дождитесь штатной проверки; эта справка не создаёт сетевую нагрузку.")
            if(listOf(https,dns,tg,yt).all{it=="PASS"}) Text("Локальные пробы ответили. Если приложение всё ещё не работает, возможна проблема полного TUN-пути, его настроек, аккаунта или другого DC. Зелёные пробы этого не исключают.")
        }
    },confirmButton={TextButton(onClick={help=false}) {Text("Понятно")}})
}
