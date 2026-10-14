package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner

/** Actual root-free capabilities, never a fake RAM cleaner or force-stop button. */
@Composable
fun DeviceScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by DeviceCoach.state.collectAsState()
    val phone by DeviceCoach.phone.collectAsState()
    val gameDeadline by GameSession.deadline.collectAsState()
    val owner = LocalLifecycleOwner.current
    val gaming = GameSession.valid(gameDeadline, android.os.SystemClock.elapsedRealtime())
    val tips by DeviceCoach.advice.collectAsState()
    val brain by PhoneBrain.status.collectAsState()
    val settings by Store.settings.collectAsState()
    var apps by remember { mutableStateOf<List<DeviceCoach.AppUse>>(emptyList()) }
    var background by remember { mutableStateOf(DeviceCoach.backgroundEnabled(ctx)) }
    var message by remember { mutableStateOf("") }
    fun sample() { scope.launch { withContext(Dispatchers.IO) { runCatching { DeviceCoach.sample(ctx) } }; message = "Показаны доступные системные данные. Чужие приложения не изменены." } }
    fun open(action: String, pkg: String? = null) { if (!DeviceCoach.open(ctx, action, pkg)) message = "Этот экран настроек недоступен на устройстве" }
    fun refreshApps() { scope.launch { apps = withContext(Dispatchers.IO) { DeviceCoach.recentApps(ctx) } } }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refreshApps()
            while (true) { withContext(Dispatchers.IO) { runCatching { DeviceCoach.sample(ctx) } }; kotlinx.coroutines.delay(10000) }
        }
    }
    LaunchedEffect(gameDeadline) {
        if (gameDeadline > 0) {
            kotlinx.coroutines.delay((gameDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            if (GameSession.deadline.value == gameDeadline) { GameSession.set(false); PhonePower.refresh(ctx) }
        }
    }
    fun gb(bytes: Long) = if (bytes > 0) "%.1f ГБ".format(bytes / 1e9) else "нет данных"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Телефон · без root", fontSize = 22.sp)
        Text("Отдельно от VPN: системные показатели, локальное обучение и план действий. Android не разрешает этому приложению автоматически закрывать, очищать кэш или ограничивать чужие приложения.", fontSize = 13.sp)
        snapshot?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ОЗУ: доступно ${gb(s.ramAvailable)} из ${gb(s.ramTotal)}")
                    Text(if (s.signals.lowMemory) "Android сообщает о нехватке памяти" else "Android не сообщил о нехватке памяти; занятая RAM сама по себе не проблема")
                    Text("Хранилище: свободно ${gb(s.storageAvailable)} из ${gb(s.storageTotal)}")
                    Text("Батарея: ${s.battery.takeIf { it in 1..100 }?.let { "$it%" } ?: "нет данных"} · ${if (s.signals.charging) "зарядка" else "без зарядки"}")
                    Text("Температура батареи / статус Android: ${Thermal.label(s.signals.temp, s.signals.thermal).ifEmpty { "нет данных" }}")
                    Text("Экономия энергии: ${if (s.saver) "включена" else "выключена"}")
                    Text("Обновлено ${((android.os.SystemClock.elapsedRealtime() - s.signals.now).coerceAtLeast(0) / 1000)} с назад", fontSize = 12.sp)
                }
            }
        } ?: Text("Получаем показатели…")
        Button(onClick = { sample() }, modifier = Modifier.fillMaxWidth()) { Text("Проверить телефон и составить план") }
        tips.forEach { tip ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(tip.text)
                    TextButton(onClick = { open(tip.action) }) { Text("Открыть нужные настройки") }
                }
            }
        }
        Text("Игровая сессия · до 60 минут", fontSize = 18.sp)
        Row(Modifier.fillMaxWidth()) {
            Switch(checked = gaming, onCheckedChange = { enabled ->
                GameSession.set(enabled); PhonePower.refresh(ctx)
                message = if (enabled) "Фоновое обслуживание приостановлено. Новые автоматические подключения учитывают задержку и джиттер." else "Обычная политика восстановлена"
            })
            Text("Меньше фоновой работы", Modifier.padding(12.dp))
        }
        Text("Временно откладывает облачные обновления, резервные проверки и фоновое наблюдение. Выбор учитывает EMA задержки/джиттера HTTPS, а не выдуманные потери UDP. Текущая связь не рвётся. Приоритет процессов других приложений и частоты CPU не меняются; режим сбрасывается при завершении процесса.", fontSize = 12.sp)
        Text("Непрерывность VPN и сеть", fontSize = 18.sp)
        phone?.let { p ->
            Text(if (p.batteryOptimized) "Оптимизация батареи для VPN включена. На некоторых устройствах это ограничивает фоновую работу." else "Для VPN разрешено исключение из оптимизации батареи.", fontSize = 13.sp)
            OutlinedButton(onClick = { open("vpnBattery") }, modifier = Modifier.fillMaxWidth()) { Text("Проверить разрешение работы VPN в фоне") }
            Text(if (p.sdk < 28) "Private DNS: API недоступно до Android 9" else if (p.privateDnsActive)
                "Private DNS активен${p.privateDnsHost.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}. Это не доказательство конфликта. При задержках DNS проверьте системные настройки вручную."
                else "Private DNS на доступной основной сети не активен", fontSize = 13.sp)
            OutlinedButton(onClick = { open("network") }, modifier = Modifier.fillMaxWidth()) { Text("Настройки сети и Private DNS") }
        }
        Text("Исключения из VPN: ${if (!settings.perApp) "отключены" else if (settings.onlyApps) "только выбранные приложения" else "${settings.apps.size} выбранных; локальные приложения: ${if (settings.ruAppsDirect) "прямо" else "через VPN"}"}. Изменяются в Настройках → Приложения. Исключённый трафик не защищён VPN; игровой режим не добавляет исключения автоматически.", fontSize = 12.sp)
        Text("При системном lowMemory новые необязательные задачи откладываются, проверки ограничены двумя кандидатами. Правила безопасности и базы маршрутизации не вырезаются автоматически; очистка RAM и чужого кэша не выполняется.", fontSize = 12.sp)
        Text("Отдельная обучающаяся нейросеть", fontSize = 18.sp)
        Row(Modifier.fillMaxWidth()) {
            Switch(checked = settings.phoneLearning, onCheckedChange = { enabled ->
                Store.update { it.copy(settings = it.settings.copy(phoneLearning = enabled)) }
                if (!enabled) scope.launch { withContext(Dispatchers.IO) { runCatching { PhoneBrain.reset(ctx) } } }
            })
            Text("Локальное обучение", modifier = Modifier.padding(12.dp))
        }
        Text(brain, fontSize = 13.sp)
        Text("Модель учится на будущих признаках нагрева/нехватки памяти по температуре, её изменению, зарядке, занятости RAM и интенсивности сети. Пока приложение открыто — раз в минуту. После достаточного опыта даёт прогнозы, но не управляет другими приложениями. CPU-нагрузку и расход батареи отдельных приложений Android здесь не раскрывает.", fontSize = 12.sp)
        Text("Веса и счётчики зашифрованы Android Keystore; сырые показания не сохраняются и не отправляются. Список приложений не используется для обучения.", fontSize = 12.sp)
        OutlinedButton(onClick = { scope.launch { val ok = withContext(Dispatchers.IO) { runCatching { PhoneBrain.reset(ctx) }.isSuccess }; message = if (ok) "Опыт нейросети удалён" else "Не удалось удалить опыт; повторите позже" } }) { Text("Удалить опыт") }
        Row(Modifier.fillMaxWidth()) {
            Switch(checked = background, onCheckedChange = { on -> background = on && DeviceCoach.background(ctx, true); if (!on) DeviceCoach.background(ctx, false); if (on && !background) message = "Android не разрешил запланировать наблюдение" })
            Text("Редкое фоновое наблюдение", Modifier.padding(12.dp))
        }
        Text("Опционально: системная задача примерно раз в 15 минут. Android может задерживать её; VPN не нужен. Она обновляет диагностику, но длительные промежутки не используются как минутные обучающие примеры. Не работает после принудительной остановки приложения; после перезагрузки возобновляется при открытии приложения.", fontSize = 12.sp)
        Text("Недавно использованные приложения", fontSize = 18.sp)
        Text("Время на переднем плане за последние 24 часа — не расход батареи и не список виновников нагрева. Имена остаются только в памяти текущего экрана.", fontSize = 12.sp)
        if (!AppWatch.hasAccess(ctx)) {
            OutlinedButton(onClick = { AppWatch.openAccess(ctx) }) { Text("Разрешить историю использования (необязательно)") }
        }
        TextButton(onClick = { refreshApps() }) { Text("Обновить список приложений") }
        if (apps.isEmpty()) Text("Нет доступных данных об использовании", fontSize = 12.sp)
        apps.forEach { app ->
            OutlinedButton(onClick = { open("app", app.pkg) }, modifier = Modifier.fillMaxWidth()) { Text("${app.title} · ${app.minutes} мин · настройки") }
        }
        Text("Быстрые безопасные действия", fontSize = 18.sp)
        OutlinedButton(onClick = { open("battery") }, modifier = Modifier.fillMaxWidth()) { Text("Батарея и энергосбережение") }
        OutlinedButton(onClick = { open("storage") }, modifier = Modifier.fillMaxWidth()) { Text("Место и файлы — удаление только вручную") }
        OutlinedButton(onClick = { open("display") }, modifier = Modifier.fillMaxWidth()) { Text("Яркость и экран") }
        OutlinedButton(onClick = { open("apps") }, modifier = Modifier.fillMaxWidth()) { Text("Фоновая работа приложений — системные настройки") }
        Text("Доступно на поддерживаемых Android 7+; состав датчиков и экранов зависит от производителя. Нет root, Accessibility-управления, фиктивного ускорения или физического охлаждения. Уменьшение лишней нагрузки может помочь, но результат на каждом телефоне нужно измерять.", fontSize = 12.sp)
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
    }
}
