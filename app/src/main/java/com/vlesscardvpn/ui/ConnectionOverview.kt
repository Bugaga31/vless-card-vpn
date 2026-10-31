package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.vlesscardvpn.core.*
import com.vlesscardvpn.health.HealthModule

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable
fun ConnectionOverview(status:Tunnel.Status,hideRoute:Boolean,modeLabel:String,control:@Composable ()->Unit) {
    val settings by Store.settings.collectAsState()
    val evidence by AutoCheckEvidence.frame.collectAsState()
    val runtime by MaskRuntimeEvidence.state.collectAsState()
    val owner=LocalLifecycleOwner.current
    val ctx=androidx.compose.ui.platform.LocalContext.current
    var now by remember {mutableLongStateOf(android.os.SystemClock.elapsedRealtime())}
    LaunchedEffect(owner) {owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while(true) {now=android.os.SystemClock.elapsedRealtime();kotlinx.coroutines.delay(1000)}
    }}
    val profile=AutoCheckProfile.of(settings.autoChecks)
    val current=evidence?.let {e->HomeDataMapper.proofCurrent(e.profile==profile,e.limit==settings.autoLatencyMaxMs,
        e.at,now,NetworkObservation.accepts(e.network,Net.observation(ctx)),e.runtimeEpoch==runtime.epoch,
        e.runtimeRevision==runtime.revision,runtime.config==MaskRuntimeEvidence.Config.CORE_STARTED,
        runtime.entries.none {it.engine==MaskRuntimeEvidence.Engine.UNAVAILABLE})}==true
    var showProfile by remember {mutableStateOf(false)}
    if(showProfile) ModalBottomSheet(onDismissRequest={showProfile=false}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Профиль проверок Авто",style=MaterialTheme.typography.titleLarge)
            AutoCheckSettings();HorizontalDivider();AutoLatencySettings()
            TextButton(onClick={showProfile=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Готово")}
        }
    }
    val on=status.state==Tunnel.State.CONNECTED
    val presentation=if(on && !current) status.copy(checkOk=null) else status
    Card(Modifier.fillMaxWidth().padding(top=12.dp),shape=RoundedCornerShape(24.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Режим · $modeLabel",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ConnectionPresentationPolicy.heading(presentation.state,presentation.checkOk),
                style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.onSurface)
            control()
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                val verdict=when {evidence==null->HomePresentation.Verdict.NO_DATA
                    !current->HomePresentation.Verdict.STALE
                    else->when(profile.verdict(evidence!!.probe,settings.autoLatencyMaxMs)) {
                        true->HomePresentation.Verdict.PASS;false->HomePresentation.Verdict.FAIL;null->HomePresentation.Verdict.UNKNOWN}}
                HomeStatusPill("${verdict.symbol} Тестовый SOCKS: ${verdict.label}",verdict)
                HomeStatusPill(if(runtime.tunActive) "● Системный TUN создан" else if(runtime.proxyOnly && on)
                    "— Только локальный прокси, без TUN" else "— Системный TUN не подтверждён",
                    if(runtime.tunActive) HomePresentation.Verdict.PASS else HomePresentation.Verdict.NO_DATA)
            }
            Text("TUN и проверочный SOCKS — разные контуры. Эти статусы не подтверждают трафик каждого приложения или реальный звонок.",
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(status.route.isNotBlank()) Text(if(hideRoute) "Имена маршрута скрыты" else status.route,style=MaterialTheme.typography.titleMedium)
            Text("Маска · "+when(runtime.config) {
                MaskRuntimeEvidence.Config.NO_DATA->"нет runtime-данных"
                MaskRuntimeEvidence.Config.PREPARING->"подготовка конфигурации"
                MaskRuntimeEvidence.Config.CORE_STARTED->"ядро запущено с конфигурацией"
                MaskRuntimeEvidence.Config.STOPPED->"ядро остановлено"
                MaskRuntimeEvidence.Config.ERROR->"ошибка запуска"
            },style=MaterialTheme.typography.titleSmall)
            runtime.entries.forEachIndexed {i,entry->
                Text("${i+1}. Запрошена: ${entry.requestedTitle}; в конфиге: ${entry.configuredTitle}",style=MaterialTheme.typography.bodyMedium)
                HomeStatusPill(when(entry.engine) {
                    MaskRuntimeEvidence.Engine.NOT_REQUIRED->"— ${entry.engineName}: внешний процесс не требуется"
                    MaskRuntimeEvidence.Engine.RUNNING->"● ${entry.engineName}: процесс активен"
                    MaskRuntimeEvidence.Engine.UNAVAILABLE->"✕ ${entry.engineName}: процесс не подтверждён"
                    MaskRuntimeEvidence.Engine.STOPPED->"— ${entry.engineName}: остановлен"
                },if(entry.engine==MaskRuntimeEvidence.Engine.UNAVAILABLE) HomePresentation.Verdict.FAIL else HomePresentation.Verdict.NO_DATA)
                if(entry.fallback) HomeStatusPill("! Fallback: запрошенные параметры маски изменены; это не прямой обход VPN",HomePresentation.Verdict.UNKNOWN)
            }
            OutlinedButton(onClick={showProfile=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                Text("Профиль Авто: ${AutoChecks.label(profile)} · до ${settings.autoLatencyMaxMs} мс · ${ProbeConcurrencyPolicy.normalize(settings.probeParallel)} проб",style=MaterialTheme.typography.bodyMedium)
            }
            if(settings.mode==com.vlesscardvpn.model.Mode.AUTO) {
                HomeServiceChips(HomeDataMapper.services(profile,evidence?.probe,current))
                Text("Показаны результаты локальных проб; истёкший или изменённый контекст не считается текущим успехом.",style=MaterialTheme.typography.bodySmall)
            }
            Text(if(settings.torAll) "RU-исключения выключены · Всё через Tor" else if(settings.ruAllDirect)
                "RU-домены и IPv4: напрямую · после переподключения" else if(settings.ruWhitelist)
                "RU-список: напрямую · после переподключения" else "RU-список: через VPN",style=MaterialTheme.typography.bodySmall)
            if(status.state==Tunnel.State.ERROR || status.checkOk==false) Text("Маршрут требует проверки. Причины — в диагностике; это не доказательство блокировки провайдером.",style=MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ServiceHealthCard() {
    val frame by ConnectionPassport.modules.collectAsState()
    val autoSettings by Store.settings.collectAsState()
    val profile=AutoCheckProfile.of(autoSettings.autoChecks)
    val selectedEvidence by AutoCheckEvidence.frame.collectAsState()
    val stabilityRevision by TelegramStability.revision.collectAsState()
    val owner=LocalLifecycleOwner.current
    var now by remember {mutableLongStateOf(android.os.SystemClock.elapsedRealtime())}
    LaunchedEffect(owner) {owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while(true) {now=android.os.SystemClock.elapsedRealtime();kotlinx.coroutines.delay(5000)}
    }}
    Card(Modifier.fillMaxWidth().padding(top=12.dp),shape=RoundedCornerShape(12.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Проверки сервисов",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            val evidence=selectedEvidence?.takeIf {it.profile==profile && it.limit==autoSettings.autoLatencyMaxMs && now>=it.at && now-it.at<=90_000L}
            listOf(HealthModule.HTTPS to "Интернет · HTTPS",HealthModule.DNS to "DNS через SOCKS",
                HealthModule.TELEGRAM to "Telegram · ответ дата-центра",HealthModule.YOUTUBE to "YouTube · медиа").forEach {(module,title)->
                val disabled=autoSettings.mode==com.vlesscardvpn.model.Mode.AUTO && (module==HealthModule.TELEGRAM && !profile.telegram || module==HealthModule.YOUTUBE && !profile.youtube)
                val activeFrame=if(autoSettings.mode==com.vlesscardvpn.model.Mode.AUTO && (selectedEvidence?.profile!=profile || selectedEvidence?.limit!=autoSettings.autoLatencyMaxMs)) null else frame
                val observation=activeFrame?.assessment?.observations?.firstOrNull {it.module==module}
                val sampledAt=activeFrame?.sampledAt?.get(module)
                val selectedModule=autoSettings.mode==com.vlesscardvpn.model.Mode.AUTO && module in setOf(HealthModule.TELEGRAM,HealthModule.YOUTUBE)
                val p=if(selectedModule) ConnectionPresentationPolicy.requiredProbe(observation,sampledAt,now,!disabled)
                    else ConnectionPresentationPolicy.probe(observation,sampledAt,now)
                val tone=when(p.status) {"PASS"->Good;"FAIL"->Bad;"SLOW"->Warn;else->MaterialTheme.colorScheme.onSurfaceVariant}
                // A column, not a fixed-width badge row: survives narrow screens and large fonts.
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants=true){}) {
                    Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium)
                    val note=if(!disabled && selectedModule && p.status=="UNKNOWN") evidence?.probe?.serviceNotes?.get(if(module==HealthModule.TELEGRAM) "telegram" else "youtube") else null
                    Text((if(disabled) "Отключено в Авто" else if(note!=null) ProbeTaskNotes.label(note) else p.label)+(p.latencyMs?.takeIf {!disabled}?.let {" · $it мс"} ?: ""),fontSize=14.sp,color=tone)
                }
            }
            if(autoSettings.mode==com.vlesscardvpn.model.Mode.AUTO) {
                Text("Выбрано в Авто: ${AutoChecks.label(profile)}",fontSize=14.sp)
                listOf("stability" to "Telegram · устойчивость серии", "voice" to "Звонки · доступность UDP", "discord" to "Discord · gateway", "instagram" to "Instagram · веб-доступ").forEach {(id,title)->
                    val value=when(id) {"stability"->evidence?.probe?.stableOk; "voice"->evidence?.probe?.callsOk; "instagram"->evidence?.probe?.instagramOk; else->evidence?.probe?.discordOk}
                    Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium)
                    Text(if(id !in profile.selected) "Отключено в Авто" else when(value) {true->ServiceProbeStatus.label(true,true,evidence?.probe?.serviceNotes?.get(id));false->ServiceProbeStatus.label(true,false,evidence?.probe?.serviceNotes?.get(id));null->"Обязательная проверка: "+ProbeTaskNotes.label(evidence?.probe?.serviceNotes?.get(id))},fontSize=14.sp,
                        color=if(id in profile.selected && value==false) Warn else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val stability = remember(stabilityRevision,now) { TelegramStability.summary(now) }
                Text("Telegram · стабильность",fontSize=14.sp,fontWeight=FontWeight.Medium)
                Text(LinkStabilityPolicy.label(stability),fontSize=14.sp,
                    color=if(stability.stable==false) Warn else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Настройки → Автоматический выбор → Что проверять в Авто",fontSize = 14.sp)
            Text("Discord gateway и короткий STUN-тест не подтверждают вход в аккаунт или реальный звонок. Tor не переносит UDP.",fontSize = 14.sp)
            Text("Локальные пробы через SOCKS. Ответ дата-центра не подтверждает работу приложения Telegram, аккаунта, звонков или полного TUN-пути.",
                fontSize=14.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
