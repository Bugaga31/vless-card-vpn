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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
        var typed by remember(parallel) {mutableStateOf(parallel.toString())}
        val parsed=ProbeConcurrencyPolicy.parse(typed)
        OutlinedTextField(value=typed,onValueChange={text->
                typed=text.filter(Char::isDigit).take(4)
                ProbeConcurrencyPolicy.parse(typed)?.let {n->if(n!=parallel) Store.update {state->state.copy(settings=state.settings.copy(probeParallel=n))}}
            },
            label={Text("Своё число, 1–${ProbeConcurrencyPolicy.MAX}")},singleLine=true,isError=parsed==null,
            supportingText={Text(if(parsed==null) "Введите целое число от 1 до ${ProbeConcurrencyPolicy.MAX}" else "Больше ${ProbeConcurrencyPolicy.MAX} сохраняется как ${ProbeConcurrencyPolicy.MAX}")},
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number,imeAction=ImeAction.Done),
            modifier=Modifier.fillMaxWidth().semantics {contentDescription="Своё число одновременных проверок"})
        val inFlight by produceState(ProbeInFlight.current to ProbeInFlight.peak) {
            while(true) {value=ProbeInFlight.current to ProbeInFlight.peak;kotlinx.coroutines.delay(1000)}
        }
        val ceiling=ProbeConcurrencyPolicy.deviceCeiling
        Text("По умолчанию ${ProbeConcurrencyPolicy.DEFAULT}. Выбранный предел $parallel" +
            (if(slots<parallel) "; на этом устройстве (32-битный процесс) не больше $ceiling" else "") +
            ". Сейчас выполняется ${inFlight.first}, максимум с запуска приложения ${inFlight.second}. Это фактическое число маршрутов в работе, а не обещание: в волне не бывает больше кандидатов, чем найдено серверов. Новый лимит действует со следующей волны.",style=MaterialTheme.typography.bodyMedium)
        Text("Каждая одновременная проверка — отдельный маршрут в тестовом ядре, свои сокеты и несколько потоков. Сотни проверок сильнее нагружают процессор, память и батарею и могут нагреть телефон; это не измерено на реальных устройствах. Если телефон греется или Авто ведёт себя хуже, уменьшите число.",style=MaterialTheme.typography.bodyMedium)
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
