package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.*

@Composable
fun AutoCheckSettings() {
    val settings by Store.settings.collectAsState()
    val selected=AutoCheckProfile.of(settings.autoChecks).selected
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Что проверять в Авто",style=MaterialTheme.typography.titleMedium)
        Text("HTTPS проверяется всегда. Выбранные проверки обязательны для нового сервера и резервов.",style=MaterialTheme.typography.bodySmall)
        listOf("telegram" to "Telegram · ответ дата-центра", "stability" to "Telegram · устойчивость серии",
            "voice" to "Звонки · UDP-транспорт", "discord" to "Discord · gateway", "youtube" to "YouTube · медиа").forEach {(id,title)->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(title,Modifier.weight(1f))
                Switch(checked=id in selected,onCheckedChange={on->Store.update {state->
                    val old=AutoCheckProfile.of(state.settings.autoChecks).selected
                    state.copy(settings=state.settings.copy(autoChecks=if(on) old+id else old-id))
                }})
            }
        }
        Text("Сохранение автоматическое. Текущее соединение не обрывается; новые настройки учитываются со следующей проверки. Старые резервы должны пройти новый набор проверок.",style=MaterialTheme.typography.bodySmall)
        Text("Устойчивость включает три ответа Telegram даже при выключенном первом переключателе. Discord — ответ публичного gateway API, не вход в аккаунт. Звонки — серия STUN через этот SOCKS, не реальный звонок Telegram/Discord. Через Tor UDP не поддерживается: для Tor отключите UDP-проверку. YouTube использует небольшой объём видеотрафика. STUN-сервис Google видит запросы с выхода VPN.",style=MaterialTheme.typography.bodySmall)
    }
}
