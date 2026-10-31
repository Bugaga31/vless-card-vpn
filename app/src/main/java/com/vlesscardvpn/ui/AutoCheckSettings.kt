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
        Text("По умолчанию 5. Сейчас до $slots одновременно; выбранный предел $parallel. Новый лимит действует со следующей волны. Внутренняя оптимизация, нагрев и звонки больше не урезают выбранное число; системные ограничения Android остаются.",style=MaterialTheme.typography.bodyMedium)
        Text("В одном тестовом ядре создаются только проверяемые маршруты. Первый подходящий сервер останавливает ненужные пробы; это снижает лишнюю нагрузку, но не гарантирует отсутствие нагрева.",style=MaterialTheme.typography.bodyMedium)
        Text("Что проверять в Авто",style=MaterialTheme.typography.titleMedium)
        Text("ROKI · эксперимент на подграфе FlyWire. Учится на завершённых проверках этой сессии и меняет только порядок кандидатов после 8 результатов. Не целый мозг мухи; неизвестные проверки не разрешает.",style=MaterialTheme.typography.bodyMedium)
        Text("HTTPS проверяется всегда. Выбранные проверки обязательны для нового сервера и резервов.",style=MaterialTheme.typography.bodyMedium)
        listOf("telegram" to "Telegram · ответ дата-центра", "stability" to "Telegram · устойчивость серии",
            "voice" to "Звонки · доступность UDP", "discord" to "Discord · gateway", "youtube" to "YouTube · веб-доступ", "instagram" to "Instagram · веб-доступ").forEach {(id,title)->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(title,Modifier.weight(1f))
                Switch(modifier=Modifier.semantics {contentDescription="Авто: $title"},checked=id in selected,onCheckedChange={on->Store.update {state->
                    val old=AutoCheckProfile.of(state.settings.autoChecks).selected
                    state.copy(settings=state.settings.copy(autoChecks=if(on) old+id else old-id))
                }})
            }
        }
        Text("Сохранение автоматическое. Текущее соединение не обрывается; новые настройки учитываются со следующей проверки. Старые резервы должны пройти новый набор проверок.",style=MaterialTheme.typography.bodyMedium)
        Text("Устойчивость включает три ответа Telegram даже при выключенном первом переключателе. Discord — публичный API и WebSocket HELLO через тот же SOCKS; не вход в аккаунт и не голосовой канал. Звонки — короткая UDP-серия через этот SOCKS с запасной STUN-целью; доступность транспорта, не реальный звонок и не оценка кодека/TURN. Через Tor UDP не поддерживается: для Tor отключите UDP-проверку. YouTube — один короткий HTTPS-запрос generate_204 через тот же SOCKS: ожидается ответ 204, без скачивания видео. Подтверждает веб-доступ, не воспроизведение, аккаунт или скорость. Instagram — только публичная веб-страница, не лента/Reels/аккаунт. STUN-сервисы Google/Cloudflare видят запросы с выхода VPN.",style=MaterialTheme.typography.bodyMedium)
    }
}
