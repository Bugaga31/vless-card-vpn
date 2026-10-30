package com.vlesscardvpn.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.*
import com.vlesscardvpn.watson.*
import kotlinx.coroutines.*

@Composable
fun WatsonEntry(onOpen:()->Unit) {
    OutlinedButton(onClick=onOpen,modifier=Modifier.fillMaxWidth()) {Text("ВАТСОН · приложения и приватность")}
}

@Composable
private fun WatsonScot() {
    val sprite=listOf("....GGGG....","...GGGGGG...","..KKKKKKKK..","...SSSSSS...","...SKSSKS...","...RRRRRR...","....RRRR....","..BBBBBBBB..","..BBWWBBBB..","...TTTTTT...","...TKTKTT...","...TTKTTT...","...SS..SS...","..KKK..KKK..")
    val palette=mapOf('G' to Color(0xFF377B60),'K' to Color(0xFF192E40),'S' to Color(0xFFF2C295),
        'R' to Color(0xFFA94E35),'B' to Color(0xFF416481),'W' to Color(0xFFE8E3D6),'T' to Color(0xFF3D806A))
    Canvas(Modifier.size(72.dp,84.dp).semantics{contentDescription="ВАТСОН — пиксельный шотландец в берете и килте"}) {
        val unit=minOf(size.width/12,size.height/14)
        sprite.forEachIndexed {y,row->row.forEachIndexed {x,c->palette[c]?.let {drawRect(it,Offset(x*unit,y*unit),Size(unit,unit))}}}
    }
}

@Composable
fun WatsonScreen(openPhone:()->Unit) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val settings by Store.settings.collectAsState()
    val evidence by AutoCheckEvidence.frame.collectAsState()
    val tunnel by Tunnel.status.collectAsState()
    var apps by remember {mutableStateOf<WatsonApps.Snapshot?>(null)}
    var scanning by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var query by remember {mutableStateOf("")}
    var expanded by remember {mutableStateOf(setOf<String>())}
    var url by remember {mutableStateOf("")}
    var urlResult by remember {mutableStateOf<WatsonUrl.Result?>(null)}
    var confirm by remember {mutableStateOf(false)}
    var undo by remember {mutableStateOf<Pair<Boolean,Boolean>?>(null)}
    fun open(action:String,pkg:String?=null) {
        message=try {
            ctx.startActivity(Intent(action).apply{if(pkg!=null)data=Uri.parse("package:$pkg");addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)})
            "Открыты системные настройки. Изменения выполняете вы; после возвращения повторите аудит."
        } catch(_:Exception) {"Этот экран системных настроек недоступен."}
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Убрать прямые RU-исключения?")},
        text={Text("Будут отключены только RU-список напрямую и весь RU-трафик напрямую. Это может изменить доступность сервисов. Текущий туннель автоматически не меняется: потребуется переподключение. Это не доказательство отсутствия утечек.")},
        confirmButton={TextButton(onClick={
            val old=Store.settings.value;undo=old.ruWhitelist to old.ruAllDirect
            Store.update{it.copy(settings=it.settings.copy(ruWhitelist=false,ruAllDirect=false))}
            confirm=false;message="Прямые RU-исключения выключены в настройках. Переподключите VPN для применения."
        }){Text("Отключить исключения")}},dismissButton={TextButton(onClick={confirm=false}){Text("Отмена")}})
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=16.dp)) {
        item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            WatsonScot()
            Column(Modifier.weight(1f)) {Text("ВАТСОН",style=MaterialTheme.typography.headlineMedium)
                Text("Ваш Аналитик Технологической Сохранности, Осторожности и Надёжности",style=MaterialTheme.typography.bodySmall)
                Text("Локальный аудит · Experimental",style=MaterialTheme.typography.labelMedium)}
        }}
        item {Text("Не универсальный антивирус. Не читает сообщения и звонки, не доказывает отсутствие слежки. Нет облачной отправки, автоматической блокировки, удаления приложений или очистки чужой памяти.")}
        item {OutlinedButton(onClick=openPhone,modifier=Modifier.fillMaxWidth()){Text("Оптимизатор и диагностика телефона")}}
        item {Text("Соединение и выбранные проверки",style=MaterialTheme.typography.titleLarge)
            Text("Состояние: "+ConnectionPresentationPolicy.heading(tunnel.state,tunnel.checkOk))
            Text("Выбрано: "+AutoChecks.label(AutoCheckProfile.of(settings.autoChecks)))
            val now=android.os.SystemClock.elapsedRealtime()
            val fresh=evidence?.takeIf{it.profile==AutoCheckProfile.of(settings.autoChecks) && it.limit==settings.autoLatencyMaxMs && now>=it.at && now-it.at<=90_000L}
            Text(if(settings.mode!=com.vlesscardvpn.model.Mode.AUTO) "Сервисный профиль относится к режиму Авто."
                else if(fresh==null) "Свежего результата текущего профиля нет. Неизвестность — не успешная проверка."
                else AutoAdmissionReason.describe(fresh.probe,fresh.profile,fresh.limit))
            Text("Это локальные пробы, не доказательство работы аккаунта, реального звонка или полного пути другого приложения через TUN.",style=MaterialTheme.typography.bodySmall)
        }
        item {Text("Приватность VPN",style=MaterialTheme.typography.titleLarge)}
        items(WatsonPolicy.privacy(settings),key={"privacy:"+it.id}) {f->WatsonFact(f)}
        item {
            if(!settings.torAll && (settings.ruWhitelist || settings.ruAllDirect)) OutlinedButton(onClick={confirm=true},modifier=Modifier.fillMaxWidth()){Text("Отключить прямые RU-исключения…")}
            val previous=undo
            if(previous!=null && !settings.ruWhitelist && !settings.ruAllDirect) TextButton(onClick={
                Store.update{it.copy(settings=it.settings.copy(ruWhitelist=previous.first,ruAllDirect=previous.second))}
                undo=null;message="Прежние RU-исключения восстановлены в настройках. Для применения переподключите VPN."
            }){Text("Вернуть прежние RU-исключения")}
            OutlinedButton(onClick={open(AndroidSettings.ACTION_VPN_SETTINGS)},modifier=Modifier.fillMaxWidth()){Text("Системные настройки VPN")}
        }
        item {Text("Проверка ссылки · исследовательская модель",style=MaterialTheme.typography.titleLarge)
            Text("Модель обучена на историческом PhiUSIIL 2024 (Prasad & Chandra, UCI, CC BY 4.0). Не проверяет содержимое сайта и актуальные базы угроз. Поддерживается ограниченный набор ASCII HTTP(S)-ссылок; другие формы — неизвестность. Низкий результат не означает безопасность.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=url,onValueChange={url=it.take(8192);urlResult=null},label={Text("Вставьте ссылку без личных данных")},modifier=Modifier.fillMaxWidth(),singleLine=false,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri,autoCorrect=false))
            TextButton(onClick={urlResult=WatsonUrl.check(url)},enabled=url.isNotBlank()){Text("Проверить локально · не открывать")}
            urlResult?.let{r->Text(when {r.warning->"Исследовательское предупреждение: признаки похожи на фишинг. Не вводите пароль, проверьте источник независимым способом."
                !r.measured->"Не удалось оценить эту форму ссылки. Безопасность неизвестна."
                else->"Модель не выдала предупреждение. Безопасность всё равно неизвестна."},color=if(r.warning) Warn else MaterialTheme.colorScheme.onSurfaceVariant)}
            if(url.isNotEmpty()) TextButton(onClick={url="";urlResult=null}){Text("Очистить ссылку")}
        }
        item {Text("Приложения · манифест и разрешения",style=MaterialTheme.typography.titleLarge)
            Text("Только видимые Android приложения с иконкой запуска и VLESS Card. Скрытые, другие профили и часть системных приложений недоступны. Данные читаются по нажатию, не отправляются и не сохраняются. Это не сканирование кода на вирусы.")
            Button(onClick={scope.launch {
                scanning=true;message=""
                try {apps=withContext(Dispatchers.IO){WatsonApps.scan(ctx)};expanded=emptySet()}
                catch(e:CancellationException){throw e}
                catch(_:Exception){message="Аудит не завершён. Безопасность приложений неизвестна."}
                finally {scanning=false}
            }},enabled=!scanning,modifier=Modifier.fillMaxWidth()){Text(if(scanning) "Читаю разрешённые Android данные…" else "Проверить видимые приложения")}
            apps?.let{Text("Снимок: ${it.apps.size} приложений · "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.at)))
                if(it.incomplete) Text("Снимок частичный: лимит 300 или некоторые пакеты недоступны.")}
            if(apps!=null) OutlinedTextField(query,{query=it.take(120)},label={Text("Найти в снимке")},modifier=Modifier.fillMaxWidth())
            if(message.isNotBlank()) Text(message,color=Warn)
        }
        items(apps?.apps.orEmpty().filter{query.isBlank() || it.label.contains(query,true) || it.packageName.contains(query,true)},key={"app:"+it.packageName}) {a->
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(a.label,style=MaterialTheme.typography.titleMedium);Text(a.packageName,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={expanded=if(a.packageName in expanded) expanded-a.packageName else expanded+a.packageName}){Text(if(a.packageName in expanded) "Свернуть признаки" else "Показать признаки и ограничения")}
                if(a.packageName in expanded) {
                    WatsonPolicy.app(a).forEach{WatsonFact(it)}
                    OutlinedButton(onClick={open(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,a.packageName)}){Text("Разрешения в Android")}
                }
            }}
        }
    }
}
@Composable
private fun WatsonFact(f:WatsonFinding) {
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {Text(f.title,style=MaterialTheme.typography.titleSmall);Text(f.detail,style=MaterialTheme.typography.bodySmall)}
}
