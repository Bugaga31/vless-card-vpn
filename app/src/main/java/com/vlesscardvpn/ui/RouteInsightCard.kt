package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vlesscardvpn.core.CloudFreshness
import com.vlesscardvpn.core.Net
import com.vlesscardvpn.core.RouteInsight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RouteInsightCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val decisions by RouteInsight.decisions.collectAsState()
    val currentToken by RouteInsight.currentNetworkToken.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var now by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(expanded, lifecycleOwner) {
        if (expanded) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val network = withContext(Dispatchers.IO) { Net.key(context) }
                RouteInsight.currentNetworkToken.value = RouteInsight.token(network)
                now = System.currentTimeMillis()
                delay(30_000)
            }
        }
    }

    val decision = decisions.firstOrNull()
    val current = decision?.let { currentToken?.let { token -> token == it.networkToken } }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .18f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Почему этот маршрут?", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (decision == null) "Автоподбор ещё не записал решение"
                        else "История Auto · ${timeLabel(decision.decidedAt, now)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Свернуть объяснение" else "Раскрыть объяснение")
                }
            }
            if (expanded && decision != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                Text("Это историческая запись выбора, не проверка текущего туннеля и не гарантия самого быстрого маршрута.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (current == false) Text("Решение относится к прежней сети. Старые скорость и сервисные проверки не подтверждают новую сеть.",
                    color = Warn, style = MaterialTheme.typography.bodySmall)
                if (current == null) Text("Сверяю сеть для этого решения…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                Text("Этап: ${if (decision.stage == RouteInsight.Stage.FINALIST) "финалист перед передачей на подключение" else "изменение выбранного пула"}")
                Text("Критерий: ${if (decision.criterion == RouteInsight.Criterion.GAME_PROFILE) "игровой профиль" else "загрузка"}")
                Text("Размер входного списка этапа: ${decision.sourceListSize}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (decision.modelNetworkScoped) "Наблюдения модели этой сети: ${decision.modelObservations}"
                    else "Глобальные наблюдения модели: ${decision.modelObservations}; это не опыт текущего Wi-Fi",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                decision.facts.forEach { fact ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Маршрут ${fact.index}", fontWeight = FontWeight.Medium)
                        Text(if (fact.localSpeedKbps > 0) "Локальная скорость: ${fact.localSpeedKbps} кбит/с · ${timeLabel(fact.localSpeedAt, now)}"
                            else "Локальная скорость: нет актуального измерения", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Опыт этой сети/маски: ${fact.localSuccesses} успехов из ${fact.localChecks} · ${timeLabel(fact.localEvidenceAt, now)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("YouTube: ${serviceLabel(fact.youtube)} · Telegram: ${serviceLabel(fact.telegram)}" +
                            if (fact.serviceCheckedAt > 0) " · ${timeLabel(fact.serviceCheckedAt, now)}" else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (fact.cloudSpeedAt > 0) "Облачный замер скорости: ${timeLabel(fact.cloudSpeedAt, now)}"
                            else "Облачный замер скорости: неизвестен или устарел", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (fact.manifestRatingHint) Text("Есть рейтинговая подсказка манифеста; возраст без замера неизвестен, это не локальная проверка",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (fact.operatorRatingHint) Text("Есть операторская рейтинговая подсказка; её возраст неизвестен",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
                TimelineBlock("Облако скоростей", decision.speedTimeline, now, false)
                TimelineBlock("Манифест", decision.manifestTimeline, now, true)
            }
        }
    }
}

@Composable
private fun TimelineBlock(title: String, timeline: CloudFreshness.Timeline, now: Long, manifest: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.Medium)
        Text("Тело получено: ${timeLabel(timeline.downloadedAt, now)} · HTTP проверен: ${timeLabel(timeline.checkedAt, now)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text("Опубликовано: ${timeLabel(timeline.publishedAt, now)}" +
            if (manifest) " · истекает: ${timeLabel(timeline.expiresAt, now)}" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (manifest && CloudFreshness.expired(timeline, now))
            Text("Манифест просрочен: возраст виден, его маршруты и рецепты не применяются.", color = Warn,
                style = MaterialTheme.typography.bodySmall)
        if (!manifest && timeline.publishedAt > 0 && !CloudFreshness.isFreshMeasurement(timeline.publishedAt, now))
            Text("Публикация старше часа: облачные скорости не сертифицируются.", color = Warn,
                style = MaterialTheme.typography.bodySmall)
    }
}

private fun serviceLabel(result: Boolean?): String = when (result) {
    true -> "прошёл"
    false -> "не прошёл"
    null -> "нет актуальной проверки"
}

private fun timeLabel(timestamp: Long, now: Long): String {
    if (timestamp <= 0) return "неизвестно"
    if (now < timestamp) return "возраст неизвестен (время устройства изменилось)"
    val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))
    val age = now - timestamp
    val ageText = when {
        age < 60_000 -> "меньше минуты назад"
        age < 3_600_000 -> "${age / 60_000} мин назад"
        age < 86_400_000 -> "${age / 3_600_000} ч назад"
        else -> "${age / 86_400_000} дн назад"
    }
    return "$date · $ageText"
}