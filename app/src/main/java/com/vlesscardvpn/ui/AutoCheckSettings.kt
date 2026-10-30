package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.vlesscardvpn.core.*

@Composable
fun AutoCheckSettings() {
    val settings by Store.settings.collectAsState()
    val selected=AutoCheckProfile.of(settings.autoChecks).selected
    val parallel=ProbeConcurrencyPolicy.normalize(settings.probeParallel)
    val power by PhonePower.state.collectAsState()
    val slots=ProbeConcurrencyPolicy.limit(parallel,parallel,power,true)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Одновременные проверки серверов",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ProbeConcurrencyPolicy.choices.forEach {n->
                FilterChip(selected=parallel==n,onClick={Store.update {state->state.copy(settings=state.settings.copy(probeParallel=n))}},
                    modifier=Modifier.semantics {contentDescription="Проверять одновременно до $n серверов"},label={Text(n.toString())})
            }
        }
        Text("По умолчанию 5. Сейчас до $slots одновременно; выбранный предел $parallel. В следующей волне действует новый лимит. Нагрев, нехватка памяти и звонок могут временно снизить его.",style=MaterialTheme.typography.bodySmall)
        Text("В одном тестовом ядре создаются только проверяемые маршруты. Первый подходящий сервер останавливает ненужные пробы; это снижает лишнюю нагрузку, но не гарантирует отсутствие нагрева.",style=MaterialTheme.typography.bodySmall)
        Text("Что проверять в Авто",style=MaterialTheme.typography.titleMedium)
        Text("HTTPS проверяется всегда. Выбранные проверки обязательны для нового сервера и резервов.",style=MaterialTheme.typography.bodySmall)
        listOf("telegram" to "Telegram · ответ дата-центра", "stability" to "Telegram · устойчивость серии",
            "voice" to "Звонки · UDP-транспорт", "discord" to "Discord · gateway", "youtube" to "YouTube · медиа").forEach {(id,title)->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(title,Modifier.weight(1f))
                Switch(modifier=Modifier.semantics {contentDescription="Авто: $title"},checked=id in selected,onCheckedChange={on->Store.update {state->
                    val old=AutoCheckProfile.of(state.settings.autoChecks).selected
                    state.copy(settings=state.settings.copy(autoChecks=if(on) old+id else old-id))
                }})
            }
        }
        Text("Сохранение автоматическое. Текущее соединение не обрывается; новые настройки учитываются со следующей проверки. Старые резервы должны пройти новый набор проверок.",style=MaterialTheme.typography.bodySmall)
        Text("Устойчивость включает три ответа Telegram даже при выключенном первом переключателе. Discord — ответ публичного gateway API, не вход в аккаунт. Звонки — серия STUN через этот SOCKS, не реальный звонок Telegram/Discord. Через Tor UDP не поддерживается: для Tor отключите UDP-проверку. YouTube использует небольшой объём видеотрафика. STUN-сервис Google видит запросы с выхода VPN.",style=MaterialTheme.typography.bodySmall)
    }
}
