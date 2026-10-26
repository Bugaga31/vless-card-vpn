package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vlesscardvpn.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val power by PhonePower.state.collectAsState()
    var previousPreset by remember { mutableStateOf<com.vlesscardvpn.model.Settings?>(null) }
    var apps by remember { mutableStateOf<List<DeviceCoach.AppUse>>(emptyList()) }
    var showAllApps by rememberSaveable { mutableStateOf(false) }
    var background by remember { mutableStateOf(DeviceCoach.backgroundEnabled(ctx)) }
    var message by remember { mutableStateOf("") }

    fun sample() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DeviceCoach.sample(ctx) } }
            message = if (result.isSuccess) "Показаны доступные системные данные. Другие приложения не изменены." else "Не удалось прочитать системные показатели."
        }
    }

    fun open(action: String, pkg: String? = null) {
        if (!DeviceCoach.open(ctx, action, pkg)) message = "Этот экран настроек недоступен на устройстве"
    }

    fun refreshApps() {
        scope.launch { apps = withContext(Dispatchers.IO) { DeviceCoach.recentApps(ctx) } }
    }

    fun setProfile(id: String) {
        previousPreset = Store.settings.value
        Store.update { it.copy(settings = DeviceEfficiency.preset(it.settings, id)) }
        PhonePower.refresh(ctx)
        message = "Профиль применён без перезапуска VPN. Изменена только работа VLESS Card."
    }

    LaunchedEffect(owner, settings.powerProfile, power.allowLight) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refreshApps()
            while (true) {
                withContext(Dispatchers.IO) { runCatching { DeviceCoach.sample(ctx) } }
                kotlinx.coroutines.delay(ExperiencePolicy.sampleMs(settings.powerProfile == "eco", power.allowLight))
            }
        }
    }

    LaunchedEffect(gameDeadline) {
        if (gameDeadline > 0) {
            kotlinx.coroutines.delay((gameDeadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            if (GameSession.deadline.value == gameDeadline) {
                GameSession.set(false)
                PhonePower.refresh(ctx)
            }
        }
    }

    fun gb(bytes: Long) = if (bytes > 0) "%.1f ГБ".format(bytes / 1e9) else "нет данных"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Телефон", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Диагностика и бережная работа VPN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Text("Без root", modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
            }
        }

        DeviceSection("Состояние устройства", "Только показатели, которые Android разрешает прочитать.") {
            snapshot?.let { s ->
                DeviceFact("Оперативная память", "${gb(s.ramAvailable)} свободно из ${gb(s.ramTotal)}")
                Text(
                    if (s.signals.lowMemory) "Android сообщает о нехватке памяти. Необязательная работа будет ограничена."
                    else "Android не сообщил о нехватке памяти; занятая RAM сама по себе не проблема.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider()
                DeviceFact("Хранилище", "${gb(s.storageAvailable)} свободно из ${gb(s.storageTotal)}")
                DeviceFact("Батарея", "${s.battery.takeIf { it in 1..100 }?.let { "$it%" } ?: "нет данных"} · ${if (s.signals.charging) "зарядка" else "без зарядки"}")
                DeviceFact("Температура", Thermal.label(s.signals.temp, s.signals.thermal).ifEmpty { "нет данных" })
                DeviceFact("Энергосбережение Android", if (s.saver) "включено" else "выключено")
                Text(
                    "Обновлено ${((android.os.SystemClock.elapsedRealtime() - s.signals.now).coerceAtLeast(0) / 1000)} с назад",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } ?: Text("Получаем показатели…", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { sample() }, modifier = Modifier.fillMaxWidth()) { Text("Обновить показатели") }
        }

        DeviceSection("Нагрузка VLESS Card", "Профиль меняет только необязательные задачи приложения.") {
            Surface(
                color = if (power.allowLight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(power.reason, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                if (!power.screenInteractive && settings.quietScreen) "Тихий режим при выключенном экране приостанавливает фоновые улучшения; зарядка его не отменяет."
                else if (!power.screenInteractive) "При выключенном экране фоновые проверки остаются ограниченными. Зарядка не включает быстрый режим автоматически."
                else "VPN и шифрование не отключаются. Защита от нагрева, низкого заряда и нехватки памяти важнее выбранной скорости.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("eco" to "Экономный", "balanced" to "Баланс", "fast" to "Быстрый").forEach { (id, title) ->
                    FilterChip(
                        selected = settings.powerProfile == id,
                        onClick = { setProfile(id) },
                        modifier = Modifier.weight(1f),
                        label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp) }
                    )
                }
            }
            previousPreset?.let { previous ->
                TextButton(onClick = {
                    Store.update { it.copy(settings = DeviceEfficiency.restore(it.settings, previous)) }
                    previousPreset = null
                    PhonePower.refresh(ctx)
                    message = "Предыдущий профиль восстановлен"
                }, modifier = Modifier.fillMaxWidth()) { Text("Отменить смену профиля") }
            }
            Text(
                when (settings.powerProfile) {
                    "eco" -> "Экономный профиль убирает анимацию волн и снижает необязательную нагрузку."
                    "fast" -> "Быстрый профиль увеличивает пределы только при нормальном состоянии телефона."
                    else -> "Баланс выбирает умеренную нагрузку и сохраняет защитные ограничения."
                },
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            DeviceFact("Фоновая политика", "до ${power.tcpParallel} TCP / ${power.realParallel} полных проверок")
            Text(
                "Ручная проверка серверов использует более низкий предел. Диагностика этого экрана обновляется примерно раз в ${ExperiencePolicy.sampleMs(settings.powerProfile == "eco", power.allowLight) / 1000} с.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Нет очистки RAM, принудительной остановки приложений, управления частотой CPU или физического охлаждения.", style = MaterialTheme.typography.bodySmall)
        }

        if (tips.isNotEmpty()) DeviceSection("Рекомендации", "Без автоматических изменений системных настроек.") {
            tips.forEach { tip ->
                Text(tip.text, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { open(tip.action) }, modifier = Modifier.fillMaxWidth()) { Text("Открыть нужные настройки") }
            }
        }

        DeviceSection("Игровая сессия", "До 60 минут. Текущая VPN-связь не разрывается.") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = gaming, onCheckedChange = { enabled ->
                    GameSession.set(enabled)
                    PhonePower.refresh(ctx)
                    message = if (enabled) "Фоновое обслуживание приостановлено. Новые автоматические подключения учитывают задержку и джиттер." else "Обычная политика восстановлена"
                })
                Text(if (gaming) "Включена" else "Выключена", style = MaterialTheme.typography.bodyMedium)
            }
            DetailDisclosure("Что меняется", "Временно откладываются облачные обновления, резервные проверки и фоновое наблюдение. Выбор учитывает EMA задержки/джиттера HTTPS, а не предполагаемые потери UDP. Приоритет процессов других приложений и частоты CPU не меняются; режим сбрасывается при завершении процесса.")
        }

        DeviceSection("Непрерывность VPN и сеть", "Эти параметры проверяются, но не переключаются автоматически.") {
            phone?.let { p ->
                Text(
                    if (p.batteryOptimized) "Оптимизация батареи для VPN включена. На некоторых устройствах это ограничивает фоновую работу."
                    else "Для VPN разрешено исключение из оптимизации батареи.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { open("vpnBattery") }, modifier = Modifier.fillMaxWidth()) { Text("Проверить работу VPN в фоне") }
                Text(
                    if (p.sdk < 28) "Private DNS: API недоступно до Android 9"
                    else if (p.privateDnsActive) "Private DNS активен${p.privateDnsHost.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}. Это не доказывает конфликт; при задержках проверьте системные настройки вручную."
                    else "Private DNS на доступной основной сети не активен",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { open("network") }, modifier = Modifier.fillMaxWidth()) { Text("Настройки сети и Private DNS") }
            } ?: Text("Проверяем состояние разрешений…", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(
                "Исключения из VPN: ${if (!settings.perApp) "отключены" else if (settings.onlyApps) "только выбранные приложения" else "${settings.apps.size} выбранных; локальные приложения: ${if (settings.ruAppsDirect) "прямо" else "через VPN"}"}.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Исключённый трафик не защищён VPN; игровой режим не добавляет исключения автоматически.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DetailDisclosure("Как работает ограничение памяти", "При системном lowMemory новые необязательные задачи откладываются, а проверки ограничиваются. Правила безопасности и базы маршрутизации не удаляются автоматически; очистка RAM и чужого кэша не выполняется.")
        }

        DeviceSection("Локальное обучение", "Не отправляет сырые показания и не управляет другими приложениями.") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = settings.phoneLearning, onCheckedChange = { enabled ->
                    Store.update { it.copy(settings = it.settings.copy(phoneLearning = enabled)) }
                    if (!enabled) scope.launch { withContext(Dispatchers.IO) { runCatching { PhoneBrain.reset(ctx) } } }
                })
                Text(if (settings.phoneLearning) "Обучение включено" else "Обучение выключено", style = MaterialTheme.typography.bodyMedium)
            }
            Text(brain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DetailDisclosure("Что изучается", "Модель анализирует будущие признаки нагрева и нехватки памяти по температуре, её изменению, зарядке, занятости RAM и интенсивности сети. Пока приложение открыто — примерно раз в минуту. После достаточного опыта она даёт прогнозы, но не управляет другими приложениями. Android не раскрывает здесь CPU-нагрузку и расход батареи отдельных приложений.")
            DetailDisclosure("Хранение данных", "Веса и счётчики зашифрованы Android Keystore; сырые показания не сохраняются и не отправляются. Список приложений не используется для обучения.")
            OutlinedButton(onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { PhoneBrain.reset(ctx) }.isSuccess }
                    message = if (ok) "Опыт нейросети удалён" else "Не удалось удалить опыт; повторите позже"
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Удалить опыт обучения") }
        }

        DeviceSection("Фоновое наблюдение", "Редкая системная задача; Android может задерживать её.") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = background, onCheckedChange = { on ->
                    background = on && DeviceCoach.background(ctx, true)
                    if (!on) DeviceCoach.background(ctx, false)
                    if (on && !background) message = "Android не разрешил запланировать наблюдение"
                })
                Text("Разрешить наблюдение раз в ~15 минут", style = MaterialTheme.typography.bodyMedium)
            }
            DetailDisclosure("Ограничения", "VPN не нужен. Наблюдение обновляет диагностику, но длинные промежутки не используются как минутные обучающие примеры. Оно не работает после принудительной остановки приложения и возобновляется при следующем открытии после перезагрузки.")
        }

        DeviceSection("Недавно использованные приложения", "Время на переднем плане за сутки — не расход батареи и не причина нагрева.") {
            if (!AppWatch.hasAccess(ctx)) {
                OutlinedButton(onClick = { AppWatch.openAccess(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Разрешить историю использования (необязательно)") }
            }
            OutlinedButton(onClick = { refreshApps() }, modifier = Modifier.fillMaxWidth()) { Text("Обновить список") }
            if (apps.isEmpty()) Text("Нет доступных данных об использовании", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            apps.take(if (showAllApps) 20 else 5).forEach { app ->
                OutlinedButton(onClick = { open("app", app.pkg) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${app.title} · ${app.minutes} мин · настройки", maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
            if (apps.size > 5) {
                TextButton(onClick = { showAllApps = !showAllApps }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAllApps) "Показать меньше" else "Показать ещё ${(apps.size.coerceAtMost(20) - 5).coerceAtLeast(0)}")
                }
            }
        }

        DeviceSection("Безопасные действия", "Открывают системные настройки; удаление и изменения выполняете вы.") {
            OutlinedButton(onClick = { open("battery") }, modifier = Modifier.fillMaxWidth()) { Text("Батарея и энергосбережение") }
            OutlinedButton(onClick = { open("storage") }, modifier = Modifier.fillMaxWidth()) { Text("Место и файлы") }
            OutlinedButton(onClick = { open("display") }, modifier = Modifier.fillMaxWidth()) { Text("Яркость и экран") }
            OutlinedButton(onClick = { open("apps") }, modifier = Modifier.fillMaxWidth()) { Text("Фоновая работа приложений") }
        }

        Text(
            "Доступность датчиков и настроек зависит от производителя. Нет root, фиктивного ускорения или физического охлаждения. Снижение лишней нагрузки может помочь, но результат на конкретном телефоне нужно измерять.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (message.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(message, Modifier.fillMaxWidth().padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DeviceSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

@Composable
private fun DeviceFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.85f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.15f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
private fun DetailDisclosure(title: String, text: String) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(if (expanded) "Скрыть: $title" else title, textAlign = TextAlign.Center)
    }
    if (expanded) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
