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
    val on=status.state==Tunnel.State.CONNECTED
    val searching=status.state==Tunnel.State.CONNECTING
    val issue=status.state==Tunnel.State.ERROR || on && status.checkOk==false
    val tone=when {issue->Warn;on && status.checkOk==true->Good;else->Accent}
    Card(Modifier.fillMaxWidth().padding(top=12.dp),shape=RoundedCornerShape(12.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(searching) "Поиск · $modeLabel" else "Режим · $modeLabel",
                color=tone,fontSize=14.sp,fontWeight=FontWeight.Medium)
            Text(ConnectionPresentationPolicy.heading(status.state,status.checkOk),fontSize=22.sp,
                lineHeight=27.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurface)
            control()
            Text(when {
                (searching || on) && status.check.isNotBlank()->status.check
                searching->"Проверяем HTTPS и Telegram. Ищем до подключения; отмена — кнопкой выше."
                status.state==Tunnel.State.ERROR->"Серверы и настройки сохранены. Повторите поиск или откройте диагностику."
                on && status.checkOk==false->"Туннель подключён, но не все обязательные проверки прошли. Смотрите результаты ниже."
                on && status.checkOk==null->"Туннель запущен. Доступность сервисов ещё проверяется."
                on->"Локальные пробы подтвердили маршрут. Доступность аккаунтов и звонков проверяется отдельно."
                else->"Ищем маршрут с ответом HTTPS и Telegram до ${AutoLatencyPolicy.MAX_MS} мс."
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
                HealthModule.TELEGRAM to "Telegram · MTProto",HealthModule.YOUTUBE to "YouTube · медиа").forEach {(module,title)->
                val p=ConnectionPresentationPolicy.probe(frame?.assessment?.observations?.firstOrNull {it.module==module},frame?.at,now)
                val tone=when(p.status) {"PASS"->Good;"FAIL"->Bad;"SLOW"->Warn;else->MaterialTheme.colorScheme.onSurfaceVariant}
                // A column, not a fixed-width badge row: survives narrow screens and large fonts.
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants=true){}) {
                    Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium)
                    Text(p.label+(p.latencyMs?.let {" · $it мс"} ?: ""),fontSize=14.sp,color=tone)
                }
            }
            val stability = remember(stabilityRevision,now) { TelegramStability.summary(now) }
            Text("Telegram · стабильность",fontSize=14.sp,fontWeight=FontWeight.Medium)
            Text(LinkStabilityPolicy.label(stability),fontSize=14.sp,
                color=if(stability.stable==false) Warn else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Локальные пробы через SOCKS. Не проверка вашего аккаунта, звонков или полного TUN-пути.",
                fontSize=14.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
