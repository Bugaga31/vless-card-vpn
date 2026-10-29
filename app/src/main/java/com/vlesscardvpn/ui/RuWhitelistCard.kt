package com.vlesscardvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vlesscardvpn.model.Settings

@Composable
fun RuWhitelistCard(settings: Settings, onGeoChange: (Boolean) -> Unit = {}, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RU-сервисы напрямую", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Switch(checked = settings.ruWhitelist, onCheckedChange = onChange,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
            }
            Text(when {
                settings.torAll -> "В режиме «Всё через Tor» исключения выключены."
                settings.ruWhitelist -> "Банки, Госуслуги, Яндекс, VK и другие домены из списка — без VPN. Остальной трафик — через VPN."
                else -> "Список выключен: эти сервисы тоже идут через VPN."
            }, fontSize = 14.sp)
            Text("При включении трафик и DNS этих доменов видны провайдеру. Короткий список не охватывает все .ru и российские IP. «Всегда через VPN» имеет приоритет. Применится после переподключения.", fontSize = 14.sp)
            Text(com.vlesscardvpn.core.RuWhitelistPolicy.SERVICES.joinToString(" · "), fontSize = 14.sp)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RU-домены и IP напрямую", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Switch(checked = settings.ruAllDirect, onCheckedChange = onGeoChange,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp))
            }
            Text(when { settings.torAll -> "Выключено режимом «Всё через Tor»."; !settings.ruAllDirect -> "Расширение выключено. Короткий список выше действует отдельно."; else ->
                "Отдельное расширение Karing: российские домены и IPv4-подсети — без VPN. IPv6 остаётся заблокированным." }, fontSize = 14.sp)
            Text("Трафик прямых соединений и DNS доменов списка видны провайдеру. База географии может ошибаться и не содержит все серверы. «Всегда через VPN» и выбранные Tor-сервисы приоритетнее. Применится после переподключения.", fontSize = 14.sp)
            Text("База Karing · " + com.vlesscardvpn.core.KaringRuData.REVISION.take(8), fontSize = 12.sp)
        }
    }
}
