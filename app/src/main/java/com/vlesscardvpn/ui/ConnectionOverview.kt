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

@Composable
fun ConnectionOverview(status:Tunnel.Status,hideRoute:Boolean,modeLabel:String,control:@Composable ()->Unit) {
    val routeSettings by Store.settings.collectAsState()
    val selectedEvidence by AutoCheckEvidence.frame.collectAsState()
    val profilePending=routeSettings.mode==com.vlesscardvpn.model.Mode.AUTO && (selectedEvidence?.profile!=AutoCheckProfile.of(routeSettings.autoChecks) || selectedEvidence?.limit!=routeSettings.autoLatencyMaxMs)
    val presented=if(profilePending && status.state==Tunnel.State.CONNECTED) status.copy(checkOk=null,check="Набор проверок изменён или ещё не измерен. Ожидаем свежие результаты…") else status
    val on=status.state==Tunnel.State.CONNECTED
    val searching=status.state==Tunnel.State.CONNECTING
    val issue=status.state==Tunnel.State.ERROR || on && status.checkOk==false
    val tone=when {issue->Warn;on && status.checkOk==true->Good;else->Accent}
    Card(Modifier.fillMaxWidth().padding(top=12.dp),shape=RoundedCornerShape(12.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(searching) "Поиск · $modeLabel" else "Режим · $modeLabel",
                color=tone,fontSize=14.sp,fontWeight=FontWeight.Medium)
            Text(ConnectionPresentationPolicy.heading(presented.state,presented.checkOk),fontSize=22.sp,
                lineHeight=27.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurface)
            control()
            Text(when {
                (searching || on) && presented.check.isNotBlank()->presented.check
                searching->"Проверяем: ${AutoChecks.label()}. Отмена — кнопкой выше."
                status.state==Tunnel.State.ERROR->"Серверы и настройки сохранены. Повторите поиск или откройте диагностику."
                on && status.checkOk==false->"Туннель подключён, но не все обязательные проверки прошли. Смотрите результаты ниже."
                on && status.checkOk==null->"Туннель запущен. Доступность сервисов ещё проверяется."
                on->"Локальные пробы подтвердили маршрут. Доступность аккаунтов и звонков проверяется отдельно."
                else->"Проверки: ${AutoChecks.label()}. Допуск ответа до ${AutoLatencyPolicy.MAX_MS} мс."
            },fontSize=15.sp,lineHeight=22.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(status.message.isNotBlank()) Text(status.message,fontSize=14.sp,
                color=if(status.state==Tunnel.State.ERROR) Bad else MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.3f))
            Text(if(routeSettings.torAll) "RU-исключения выключены · Всё через Tor" else if(routeSettings.ruAllDirect) "RU-домены и IPv4: напрямую · после переподключения" else if(routeSettings.ruWhitelist) "RU-список: напрямую · после переподключения" else "RU-список: через VPN",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(status.route.isNotBlank()) Text(if(hideRoute) "Имена маршрута скрыты" else status.route,
                fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
            listOf(HealthModule.HTTPS to "Интернет · HTTPS",HealthModule.DNS to "DNS через SOCKS",
                HealthModule.TELEGRAM to "Telegram · ответ дата-центра",HealthModule.YOUTUBE to "YouTube · медиа").forEach {(module,title)->
                val disabled=autoSettings.mode==com.vlesscardvpn.model.Mode.AUTO && (module==HealthModule.TELEGRAM && !profile.telegram || module==HealthModule.YOUTUBE && !profile.youtube)
                val activeFrame=if(autoSettings.mode==com.vlesscardvpn.model.Mode.AUTO && (selectedEvidence?.profile!=profile || selectedEvidence?.limit!=autoSettings.autoLatencyMaxMs)) null else frame
                val p=ConnectionPresentationPolicy.probe(activeFrame?.assessment?.observations?.firstOrNull {it.module==module},activeFrame?.sampledAt?.get(module),now)
                val tone=when(p.status) {"PASS"->Good;"FAIL"->Bad;"SLOW"->Warn;else->MaterialTheme.colorScheme.onSurfaceVariant}
                // A column, not a fixed-width badge row: survives narrow screens and large fonts.
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants=true){}) {
                    Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium)
                    Text((if(disabled) "Отключено в Авто" else p.label)+(p.latencyMs?.takeIf {!disabled}?.let {" · $it мс"} ?: ""),fontSize=14.sp,color=tone)
                }
            }
            val evidence=selectedEvidence?.takeIf {it.profile==profile && it.limit==autoSettings.autoLatencyMaxMs && now>=it.at && now-it.at<=90_000L}
            if(autoSettings.mode==com.vlesscardvpn.model.Mode.AUTO) {
                Text("Выбрано в Авто: ${AutoChecks.label(profile)}",fontSize=14.sp)
                listOf("stability" to "Telegram · устойчивость серии", "voice" to "Звонки · UDP-транспорт", "discord" to "Discord · gateway").forEach {(id,title)->
                    val value=when(id) {"stability"->evidence?.probe?.stableOk; "voice"->evidence?.probe?.callsOk; else->evidence?.probe?.discordOk}
                    Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium)
                    Text(if(id !in profile.selected) "Отключено в Авто" else when(value) {true->"Локальная проверка пройдена";false->"Проверка не пройдена";null->"Не подтверждено / требуется свежая проба"},fontSize=14.sp,
                        color=if(id in profile.selected && value==false) Warn else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val stability = remember(stabilityRevision,now) { TelegramStability.summary(now) }
                Text("Telegram · стабильность",fontSize=14.sp,fontWeight=FontWeight.Medium)
                Text(LinkStabilityPolicy.label(stability),fontSize=14.sp,
                    color=if(stability.stable==false) Warn else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Настройки → Автоматический выбор → Что проверять в Авто",fontSize=12.sp)
            Text("Discord gateway и короткий STUN-тест не подтверждают вход в аккаунт или реальный звонок. Tor не переносит UDP.",fontSize=12.sp)
            Text("Локальные пробы через SOCKS. Ответ дата-центра не подтверждает работу приложения Telegram, аккаунта, звонков или полного TUN-пути.",
                fontSize=14.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
